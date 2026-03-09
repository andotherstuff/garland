//! Erasure-coded write path aligned with the v0.1 protocol spec.
//!
//! Unlike the MVP write path (`mvp_write.rs`) which copies identical encrypted
//! blocks to N servers, this module produces distinct Reed-Solomon shares per
//! server. The write output includes:
//!
//! - Upload instructions with distinct share bodies
//! - A `FileInode` describing how to reconstruct the file
//! - An encrypted inode blob for upload alongside the data shares
//!
//! The caller (Android/JNI layer) is responsible for:
//! 1. Uploading each share to its designated server
//! 2. Uploading the encrypted inode blob to all servers
//! 3. Building and signing the commit event that references the inode

use base64::{engine::general_purpose::STANDARD, Engine as _};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::crypto::{prepare_erasure_coded_upload, BlossomServer, ErasureCodingConfig};
use crate::inode::{
    build_file_inode_with_id, encrypt_inode, FileInode, InodeBlock, InodeErasure, InodeError,
    InodeShare,
};
use crate::key_hierarchy::{
    derive_file_key, derive_master_key, derive_metadata_key, KeyHierarchyError,
};
use crate::packaging::CONTENT_CAPACITY;

/// Input request for erasure-coded file write.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErasureWriteRequest {
    pub private_key_hex: String,
    pub display_name: String,
    pub mime_type: String,
    pub created_at: u64,
    pub content_b64: String,
    pub servers: Vec<String>,
    /// Number of data shards (minimum to reconstruct).
    pub data_shards: usize,
    /// Number of parity shards.
    pub parity_shards: usize,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub document_id: Option<String>,
}

/// A single upload instruction for an erasure-coded share.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ErasureUploadInstruction {
    pub server_url: String,
    pub share_id_hex: String,
    pub body_b64: String,
    /// Share index within the RS coding (0..n-1).
    pub share_index: usize,
}

/// Upload instruction for the encrypted inode blob.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct InodeUploadInstruction {
    pub server_url: String,
    pub share_id_hex: String,
    pub body_b64: String,
}

/// Complete output of the erasure-coded write planner.
#[derive(Debug, Clone, Serialize)]
pub struct ErasureWritePlan {
    /// Unique document identifier (hex-encoded 32 bytes).
    pub document_id: String,
    /// The file inode (cleartext, for local storage / commit payload).
    pub inode: FileInode,
    /// The encrypted inode blob details.
    pub encrypted_inode_hash: String,
    pub encrypted_inode_nonce_b64: String,
    /// Upload instructions for data/parity shares (one per server per block).
    pub share_uploads: Vec<ErasureUploadInstruction>,
    /// Upload instructions for the encrypted inode (one per server, identical blobs).
    pub inode_uploads: Vec<InodeUploadInstruction>,
    /// The file_id bytes (hex) for use in commit payloads.
    pub file_id_hex: String,
}

#[derive(Debug, Error)]
pub enum ErasureWriteError {
    #[error("content is not valid base64")]
    InvalidContentBase64,
    #[error("private key hex is invalid")]
    InvalidPrivateKey,
    #[error("server count {0} does not match n={1} (data_shards + parity_shards)")]
    ServerCountMismatch(usize, usize),
    #[error("crypto step failed: {0}")]
    Crypto(String),
    #[error("key derivation failed: {0}")]
    KeyDerivation(String),
    #[error("inode error: {0}")]
    Inode(String),
}

impl From<InodeError> for ErasureWriteError {
    fn from(err: InodeError) -> Self {
        ErasureWriteError::Inode(err.to_string())
    }
}

impl From<KeyHierarchyError> for ErasureWriteError {
    fn from(err: KeyHierarchyError) -> Self {
        ErasureWriteError::KeyDerivation(err.to_string())
    }
}

/// Plan an erasure-coded write for a file. Produces distinct RS shares per
/// server, a file inode, and an encrypted inode blob.
pub fn plan_erasure_write(
    request: &ErasureWriteRequest,
) -> Result<ErasureWritePlan, ErasureWriteError> {
    let content = STANDARD
        .decode(&request.content_b64)
        .map_err(|_| ErasureWriteError::InvalidContentBase64)?;

    let n = request.data_shards + request.parity_shards;
    if request.servers.len() != n {
        return Err(ErasureWriteError::ServerCountMismatch(
            request.servers.len(),
            n,
        ));
    }

    let private_key_bytes = decode_private_key(&request.private_key_hex)?;
    let document_id = match request.document_id.as_deref() {
        Some(id) => {
            validate_document_id(id)?;
            id.to_owned()
        }
        None => random_document_id_hex(),
    };

    let master_key = derive_master_key(&private_key_bytes)?;
    let metadata_key = derive_metadata_key(&master_key)?;

    // Generate the file_id first — this drives file_key derivation per spec §7.
    // The file_id is stored in the inode and used to derive the encryption key.
    let mut file_id_bytes = [0_u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut file_id_bytes);
    let file_key = derive_file_key(&master_key, &file_id_bytes)?;

    let servers: Vec<BlossomServer> = request
        .servers
        .iter()
        .map(|url| BlossomServer::new(url))
        .collect();
    let config = ErasureCodingConfig::new(request.data_shards, request.parity_shards);

    let block_chunks = split_into_blocks(&content);
    let mut inode_blocks = Vec::with_capacity(block_chunks.len());
    let mut share_uploads = Vec::with_capacity(block_chunks.len() * n);

    for (block_index, chunk) in block_chunks.iter().enumerate() {
        let nonce = random_nonce();
        let upload = prepare_erasure_coded_upload(
            file_key,
            block_index as u32,
            nonce,
            chunk,
            &servers,
            &config,
        )
        .map_err(|e| ErasureWriteError::Crypto(e.to_string()))?;

        let content_hash = hex::encode(Sha256::digest(chunk));
        let shares: Vec<InodeShare> = upload
            .shares
            .iter()
            .map(|s| InodeShare {
                id: s.share_id_hex.clone(),
                server: s.server_url.clone(),
                auth: "blob".into(),
            })
            .collect();

        inode_blocks.push(InodeBlock {
            index: block_index as u32,
            hash: content_hash,
            shares,
        });

        for (share_index, share) in upload.shares.into_iter().enumerate() {
            share_uploads.push(ErasureUploadInstruction {
                server_url: share.server_url,
                share_id_hex: share.share_id_hex,
                body_b64: STANDARD.encode(&share.body),
                share_index,
            });
        }
    }

    let inode = build_file_inode_with_id(
        &file_id_bytes,
        content.len() as u64,
        request.created_at,
        request.created_at,
        inode_blocks,
        InodeErasure::erasure_coded(request.data_shards as u8, n as u8),
    );

    let encrypted = encrypt_inode(&metadata_key, &inode)?;
    let inode_body_b64 = STANDARD.encode(&encrypted.body);
    let inode_share_id = encrypted.hash.clone();

    let inode_uploads: Vec<InodeUploadInstruction> = servers
        .iter()
        .map(|server| InodeUploadInstruction {
            server_url: server.server_url.clone(),
            share_id_hex: inode_share_id.clone(),
            body_b64: inode_body_b64.clone(),
        })
        .collect();

    Ok(ErasureWritePlan {
        document_id,
        inode,
        encrypted_inode_hash: encrypted.hash,
        encrypted_inode_nonce_b64: STANDARD.encode(encrypted.nonce),
        share_uploads,
        inode_uploads,
        file_id_hex: hex::encode(encrypted.file_id_bytes),
    })
}

fn decode_private_key(hex_str: &str) -> Result<[u8; 32], ErasureWriteError> {
    let bytes = hex::decode(hex_str).map_err(|_| ErasureWriteError::InvalidPrivateKey)?;
    bytes
        .try_into()
        .map_err(|_| ErasureWriteError::InvalidPrivateKey)
}

fn validate_document_id(id: &str) -> Result<(), ErasureWriteError> {
    let bytes = hex::decode(id).map_err(|_| ErasureWriteError::InvalidPrivateKey)?;
    if bytes.len() != 32 {
        return Err(ErasureWriteError::InvalidPrivateKey);
    }
    Ok(())
}

fn random_document_id_hex() -> String {
    let mut id = [0_u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut id);
    hex::encode(id)
}

fn random_nonce() -> [u8; 12] {
    let mut nonce = [0_u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    nonce
}

fn split_into_blocks(content: &[u8]) -> Vec<&[u8]> {
    if content.is_empty() {
        return vec![content];
    }
    content.chunks(CONTENT_CAPACITY).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::decrypt_block;
    use crate::erasure::rs_reconstruct;
    use crate::key_hierarchy::derive_file_key;
    use crate::packaging::BLOCK_SIZE;

    const TEST_KEY: &str = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a";

    fn make_servers(n: usize) -> Vec<String> {
        (0..n)
            .map(|i| format!("https://blossom{i}.example"))
            .collect()
    }

    #[test]
    fn plans_erasure_coded_single_block_write() {
        let content = b"erasure write planner test";
        let request = ErasureWriteRequest {
            private_key_hex: TEST_KEY.into(),
            display_name: "test.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: STANDARD.encode(content),
            servers: make_servers(5),
            data_shards: 3,
            parity_shards: 2,
            document_id: None,
        };

        let plan = plan_erasure_write(&request).unwrap();

        // Document ID should be 64 hex chars
        assert_eq!(plan.document_id.len(), 64);

        // Inode structure
        assert_eq!(plan.inode.inode_type, "file");
        assert_eq!(plan.inode.size, content.len() as u64);
        assert_eq!(plan.inode.blocks.len(), 1);
        assert_eq!(plan.inode.erasure.k, 3);
        assert_eq!(plan.inode.erasure.n, 5);

        // 5 share uploads (one per server)
        assert_eq!(plan.share_uploads.len(), 5);
        for (i, upload) in plan.share_uploads.iter().enumerate() {
            assert_eq!(upload.share_index, i);
            assert_eq!(upload.server_url, format!("https://blossom{i}.example"));
        }

        // Each share should have a unique ID (erasure coded)
        let ids: Vec<&str> = plan
            .share_uploads
            .iter()
            .map(|u| u.share_id_hex.as_str())
            .collect();
        for i in 0..ids.len() {
            for j in (i + 1)..ids.len() {
                assert_ne!(ids[i], ids[j], "shares {i} and {j} should differ");
            }
        }

        // 5 inode uploads (one per server, all identical)
        assert_eq!(plan.inode_uploads.len(), 5);
        assert!(plan
            .inode_uploads
            .windows(2)
            .all(|w| w[0].share_id_hex == w[1].share_id_hex));
    }

    #[test]
    fn erasure_write_plan_round_trips_content() {
        let content = b"round trip through erasure write";
        let request = ErasureWriteRequest {
            private_key_hex: TEST_KEY.into(),
            display_name: "rt.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: STANDARD.encode(content),
            servers: make_servers(5),
            data_shards: 3,
            parity_shards: 2,
            document_id: None,
        };

        let plan = plan_erasure_write(&request).unwrap();

        // Reconstruct from shares 0, 2, 4 (any 3 of 5)
        let shares: Vec<(usize, Vec<u8>)> = plan
            .share_uploads
            .iter()
            .filter(|u| u.share_index == 0 || u.share_index == 2 || u.share_index == 4)
            .map(|u| (u.share_index, STANDARD.decode(&u.body_b64).unwrap()))
            .collect();
        let available: Vec<(usize, &[u8])> = shares
            .iter()
            .map(|(idx, body)| (*idx, body.as_slice()))
            .collect();

        let reconstructed = rs_reconstruct(&available, 3, 5).unwrap();
        let encrypted_block = &reconstructed[..BLOCK_SIZE];

        // Derive the file key from the inode's file_id (per spec §7)
        let pk_bytes: [u8; 32] = hex::decode(TEST_KEY).unwrap().try_into().unwrap();
        let master_key = derive_master_key(&pk_bytes).unwrap();
        let file_id_bytes: [u8; 32] = STANDARD
            .decode(&plan.inode.file_id)
            .unwrap()
            .try_into()
            .unwrap();
        let file_key = derive_file_key(&master_key, &file_id_bytes).unwrap();

        let decrypted = decrypt_block(&file_key, 0, encrypted_block).unwrap();
        assert_eq!(decrypted, content);
    }

    #[test]
    fn rejects_server_count_mismatch() {
        let request = ErasureWriteRequest {
            private_key_hex: TEST_KEY.into(),
            display_name: "test.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: STANDARD.encode(b"test"),
            servers: make_servers(3), // Wrong: should be 5 for k=3, parity=2
            data_shards: 3,
            parity_shards: 2,
            document_id: None,
        };

        let err = plan_erasure_write(&request).unwrap_err();
        assert!(err.to_string().contains("does not match"));
    }

    #[test]
    fn plans_multi_block_erasure_write() {
        // Create content that spans 2 blocks
        let content = vec![b'x'; CONTENT_CAPACITY + 17];
        let request = ErasureWriteRequest {
            private_key_hex: TEST_KEY.into(),
            display_name: "big.bin".into(),
            mime_type: "application/octet-stream".into(),
            created_at: 1_701_907_200,
            content_b64: STANDARD.encode(&content),
            servers: make_servers(5),
            data_shards: 3,
            parity_shards: 2,
            document_id: None,
        };

        let plan = plan_erasure_write(&request).unwrap();

        assert_eq!(plan.inode.blocks.len(), 2);
        assert_eq!(plan.inode.blocks[0].index, 0);
        assert_eq!(plan.inode.blocks[1].index, 1);
        // 5 shares per block × 2 blocks = 10 share uploads
        assert_eq!(plan.share_uploads.len(), 10);
    }
}
