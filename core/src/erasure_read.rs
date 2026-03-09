//! Erasure-coded restore path aligned with the v0.1 protocol spec.
//!
//! Given an encrypted inode blob and k-of-n share bodies for each block,
//! this module reconstructs the original file content by:
//!
//! 1. Decrypting the inode with `metadata_key`
//! 2. For each block: RS-reconstructing from available shares, then decrypting
//! 3. Reassembling the original content
//!
//! The caller (Android/JNI layer) is responsible for fetching the inode blob
//! and at least k share bodies per block from Blossom servers.

use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::crypto::decrypt_block;
use crate::erasure::rs_reconstruct;
use crate::inode::{decrypt_inode, FileInode};
use crate::key_hierarchy::{derive_file_key, derive_master_key, derive_metadata_key};
use crate::packaging::BLOCK_SIZE;

/// A fetched share for a specific block, identified by its index in the RS scheme.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FetchedShare {
    /// The share index (0..n-1), determined by position in the inode's shares array.
    pub share_index: usize,
    /// Base64-encoded share body.
    pub body_b64: String,
}

/// Request to restore a file from erasure-coded shares.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErasureRestoreRequest {
    pub private_key_hex: String,
    /// Base64-encoded encrypted inode blob.
    pub encrypted_inode_b64: String,
    /// For each block (ordered by block index), the available fetched shares.
    /// At least k shares per block are required.
    pub blocks: Vec<Vec<FetchedShare>>,
}

/// Successful restore result.
#[derive(Debug, Clone)]
pub struct ErasureRestoreResult {
    /// The decrypted file inode.
    pub inode: FileInode,
    /// The reassembled file content.
    pub content: Vec<u8>,
}

#[derive(Debug, Error)]
pub enum ErasureRestoreError {
    #[error("private key hex is invalid")]
    InvalidPrivateKey,
    #[error("encrypted inode is not valid base64")]
    InvalidInodeBase64,
    #[error("inode decryption failed: {0}")]
    InodeDecryption(String),
    #[error("key derivation failed: {0}")]
    KeyDerivation(String),
    #[error("block {0}: share body is not valid base64")]
    InvalidShareBase64(u32),
    #[error("block {0}: not enough shares (need {1}, got {2})")]
    InsufficientShares(u32, usize, usize),
    #[error("block {0}: RS reconstruction failed: {1}")]
    ReconstructionFailed(u32, String),
    #[error("block {0}: decryption failed: {1}")]
    BlockDecryption(u32, String),
    #[error("block {0}: content hash mismatch (expected {1}, got {2})")]
    HashMismatch(u32, String, String),
    #[error("block count mismatch: inode has {0} blocks, request has {1}")]
    BlockCountMismatch(usize, usize),
}

/// Restore a file from its encrypted inode and erasure-coded shares.
pub fn restore_erasure_coded_file(
    request: &ErasureRestoreRequest,
) -> Result<ErasureRestoreResult, ErasureRestoreError> {
    let private_key_bytes = decode_private_key(&request.private_key_hex)?;
    let master_key = derive_master_key(&private_key_bytes)
        .map_err(|e| ErasureRestoreError::KeyDerivation(e.to_string()))?;
    let metadata_key = derive_metadata_key(&master_key)
        .map_err(|e| ErasureRestoreError::KeyDerivation(e.to_string()))?;

    // Decrypt the inode
    let encrypted_inode = STANDARD
        .decode(&request.encrypted_inode_b64)
        .map_err(|_| ErasureRestoreError::InvalidInodeBase64)?;
    let inode = decrypt_inode(&metadata_key, &encrypted_inode)
        .map_err(|e| ErasureRestoreError::InodeDecryption(e.to_string()))?;

    // Validate block count
    if inode.blocks.len() != request.blocks.len() {
        return Err(ErasureRestoreError::BlockCountMismatch(
            inode.blocks.len(),
            request.blocks.len(),
        ));
    }

    // Derive the file key from the inode's file_id
    let file_id_bytes = STANDARD
        .decode(&inode.file_id)
        .ok()
        .and_then(|b| <[u8; 32]>::try_from(b.as_slice()).ok())
        .ok_or_else(|| ErasureRestoreError::InodeDecryption("invalid file_id".into()))?;
    let file_key = derive_file_key(&master_key, &file_id_bytes)
        .map_err(|e| ErasureRestoreError::KeyDerivation(e.to_string()))?;

    let k = inode.erasure.k as usize;
    let n = inode.erasure.n as usize;

    let mut content = Vec::with_capacity(inode.size as usize);

    for (block_def, fetched_shares) in inode.blocks.iter().zip(request.blocks.iter()) {
        let block_index = block_def.index;

        if fetched_shares.len() < k {
            return Err(ErasureRestoreError::InsufficientShares(
                block_index,
                k,
                fetched_shares.len(),
            ));
        }

        // Decode share bodies
        let decoded_shares: Vec<(usize, Vec<u8>)> = fetched_shares
            .iter()
            .map(|s| {
                let body = STANDARD
                    .decode(&s.body_b64)
                    .map_err(|_| ErasureRestoreError::InvalidShareBase64(block_index))?;
                Ok((s.share_index, body))
            })
            .collect::<Result<Vec<_>, ErasureRestoreError>>()?;

        let available: Vec<(usize, &[u8])> = decoded_shares
            .iter()
            .map(|(idx, body)| (*idx, body.as_slice()))
            .collect();

        // RS reconstruct
        let reconstructed = rs_reconstruct(&available, k, n)
            .map_err(|e| ErasureRestoreError::ReconstructionFailed(block_index, e.to_string()))?;

        // The reconstructed data is the padded encrypted block. Take first BLOCK_SIZE bytes.
        if reconstructed.len() < BLOCK_SIZE {
            return Err(ErasureRestoreError::ReconstructionFailed(
                block_index,
                format!(
                    "reconstructed data too short: {} < {}",
                    reconstructed.len(),
                    BLOCK_SIZE
                ),
            ));
        }
        let encrypted_block = &reconstructed[..BLOCK_SIZE];

        // Decrypt the block
        let decrypted = decrypt_block(&file_key, block_index, encrypted_block)
            .map_err(|e| ErasureRestoreError::BlockDecryption(block_index, e.to_string()))?;

        // Verify content hash from the inode
        let actual_hash = hex::encode(Sha256::digest(&decrypted));
        if actual_hash != block_def.hash {
            return Err(ErasureRestoreError::HashMismatch(
                block_index,
                block_def.hash.clone(),
                actual_hash,
            ));
        }

        content.extend_from_slice(&decrypted);
    }

    // Truncate to actual file size (last block may have been padded by framing)
    content.truncate(inode.size as usize);

    Ok(ErasureRestoreResult { inode, content })
}

fn decode_private_key(hex_str: &str) -> Result<[u8; 32], ErasureRestoreError> {
    let bytes = hex::decode(hex_str).map_err(|_| ErasureRestoreError::InvalidPrivateKey)?;
    bytes
        .try_into()
        .map_err(|_| ErasureRestoreError::InvalidPrivateKey)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::erasure_write::{plan_erasure_write, ErasureWriteRequest};

    const TEST_KEY: &str = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a";

    fn make_servers(n: usize) -> Vec<String> {
        (0..n)
            .map(|i| format!("https://blossom{i}.example"))
            .collect()
    }

    #[test]
    fn write_then_restore_single_block() {
        let content = b"end-to-end erasure coded restore test";
        let plan = plan_erasure_write(&ErasureWriteRequest {
            private_key_hex: TEST_KEY.into(),
            display_name: "test.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: STANDARD.encode(content),
            servers: make_servers(5),
            data_shards: 3,
            parity_shards: 2,
            document_id: None,
        })
        .unwrap();

        // Simulate fetching 3 of 5 shares (indices 1, 2, 3)
        let fetched: Vec<FetchedShare> = plan
            .share_uploads
            .iter()
            .filter(|u| u.share_index >= 1 && u.share_index <= 3)
            .map(|u| FetchedShare {
                share_index: u.share_index,
                body_b64: u.body_b64.clone(),
            })
            .collect();

        let encrypted_inode_b64 = plan.inode_uploads[0].body_b64.clone();
        let result = restore_erasure_coded_file(&ErasureRestoreRequest {
            private_key_hex: TEST_KEY.into(),
            encrypted_inode_b64,
            blocks: vec![fetched],
        })
        .unwrap();

        assert_eq!(result.content, content);
        assert_eq!(result.inode.size, content.len() as u64);
    }

    #[test]
    fn write_then_restore_multi_block() {
        let content = vec![b'z'; crate::packaging::CONTENT_CAPACITY + 42];
        let plan = plan_erasure_write(&ErasureWriteRequest {
            private_key_hex: TEST_KEY.into(),
            display_name: "big.bin".into(),
            mime_type: "application/octet-stream".into(),
            created_at: 1_701_907_200,
            content_b64: STANDARD.encode(&content),
            servers: make_servers(5),
            data_shards: 3,
            parity_shards: 2,
            document_id: None,
        })
        .unwrap();

        assert_eq!(plan.inode.blocks.len(), 2);

        // Fetch 3 of 5 shares for each block (indices 0, 2, 4)
        let block0_shares: Vec<FetchedShare> = plan
            .share_uploads
            .iter()
            .filter(|u| {
                u.server_url.contains("blossom0")
                    || u.server_url.contains("blossom2")
                    || u.server_url.contains("blossom4")
            })
            .filter(|u| u.share_index == 0 || u.share_index == 2 || u.share_index == 4)
            .take(3) // first block only
            .map(|u| FetchedShare {
                share_index: u.share_index,
                body_b64: u.body_b64.clone(),
            })
            .collect();

        let block1_shares: Vec<FetchedShare> = plan
            .share_uploads
            .iter()
            .skip(5) // second block's shares start after first block
            .filter(|u| u.share_index == 0 || u.share_index == 2 || u.share_index == 4)
            .take(3)
            .map(|u| FetchedShare {
                share_index: u.share_index,
                body_b64: u.body_b64.clone(),
            })
            .collect();

        let result = restore_erasure_coded_file(&ErasureRestoreRequest {
            private_key_hex: TEST_KEY.into(),
            encrypted_inode_b64: plan.inode_uploads[0].body_b64.clone(),
            blocks: vec![block0_shares, block1_shares],
        })
        .unwrap();

        assert_eq!(result.content, content);
    }

    #[test]
    fn restore_fails_with_insufficient_shares() {
        let content = b"not enough shares";
        let plan = plan_erasure_write(&ErasureWriteRequest {
            private_key_hex: TEST_KEY.into(),
            display_name: "test.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: STANDARD.encode(content),
            servers: make_servers(5),
            data_shards: 3,
            parity_shards: 2,
            document_id: None,
        })
        .unwrap();

        // Only provide 2 shares (need 3)
        let fetched: Vec<FetchedShare> = plan
            .share_uploads
            .iter()
            .take(2)
            .map(|u| FetchedShare {
                share_index: u.share_index,
                body_b64: u.body_b64.clone(),
            })
            .collect();

        let err = restore_erasure_coded_file(&ErasureRestoreRequest {
            private_key_hex: TEST_KEY.into(),
            encrypted_inode_b64: plan.inode_uploads[0].body_b64.clone(),
            blocks: vec![fetched],
        })
        .unwrap_err();

        assert!(err.to_string().contains("not enough shares"));
    }

    #[test]
    fn restore_fails_with_wrong_key() {
        let content = b"wrong key test";
        let plan = plan_erasure_write(&ErasureWriteRequest {
            private_key_hex: TEST_KEY.into(),
            display_name: "test.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: STANDARD.encode(content),
            servers: make_servers(5),
            data_shards: 3,
            parity_shards: 2,
            document_id: None,
        })
        .unwrap();

        let wrong_key = "a".repeat(64);
        let fetched: Vec<FetchedShare> = plan
            .share_uploads
            .iter()
            .take(3)
            .map(|u| FetchedShare {
                share_index: u.share_index,
                body_b64: u.body_b64.clone(),
            })
            .collect();

        let err = restore_erasure_coded_file(&ErasureRestoreRequest {
            private_key_hex: wrong_key,
            encrypted_inode_b64: plan.inode_uploads[0].body_b64.clone(),
            blocks: vec![fetched],
        })
        .unwrap_err();

        // Should fail at inode decryption (wrong metadata key)
        assert!(err.to_string().contains("decryption failed"));
    }
}
