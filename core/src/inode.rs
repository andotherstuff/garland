use base64::{engine::general_purpose::STANDARD, Engine as _};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::crypto::{
    decrypt_metadata_block, encrypt_metadata_block, BlossomServer, CryptoError,
    ErasureReplicationUpload, ReplicationUpload,
};

/// A share descriptor inside an inode block entry.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct InodeShare {
    pub id: String,
    pub server: String,
    pub auth: String,
}

/// A single block entry inside an inode.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct InodeBlock {
    pub index: u32,
    pub hash: String,
    pub shares: Vec<InodeShare>,
}

/// Erasure coding parameters stored in the inode.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct InodeErasure {
    pub algorithm: String,
    pub k: u8,
    pub n: u8,
    pub field: String,
}

impl InodeErasure {
    pub fn replicated(n: u8) -> Self {
        Self {
            algorithm: "reed-solomon".into(),
            k: 1,
            n,
            field: "gf256".into(),
        }
    }

    pub fn erasure_coded(k: u8, n: u8) -> Self {
        Self {
            algorithm: "reed-solomon".into(),
            k,
            n,
            field: "gf256".into(),
        }
    }
}

/// A file inode per spec §7. Contains all information needed to reconstruct a file.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileInode {
    pub version: u32,
    #[serde(rename = "type")]
    pub inode_type: String,
    pub size: u64,
    pub created: u64,
    pub modified: u64,
    /// Base64-encoded 32-byte random identifier used for file key derivation.
    pub file_id: String,
    pub blocks: Vec<InodeBlock>,
    pub erasure: InodeErasure,
}

/// An encrypted inode blob ready for upload.
#[derive(Debug, Clone)]
pub struct EncryptedInode {
    /// The encrypted inode block (BLOCK_SIZE bytes).
    pub body: Vec<u8>,
    /// SHA-256 hex hash of the encrypted body.
    pub hash: String,
    /// The 12-byte random nonce used (stored in the parent reference).
    pub nonce: [u8; 12],
    /// The raw file_id bytes (for key derivation by the caller).
    pub file_id_bytes: [u8; 32],
}

#[derive(Debug, Error)]
pub enum InodeError {
    #[error("inode serialization failed")]
    Serialization,
    #[error("inode deserialization failed")]
    Deserialization,
    #[error("inode encryption failed: {0}")]
    Encryption(String),
    #[error("inode decryption failed: {0}")]
    Decryption(String),
    #[error("inode nonce is invalid")]
    InvalidNonce,
}

impl From<CryptoError> for InodeError {
    fn from(err: CryptoError) -> Self {
        InodeError::Encryption(err.to_string())
    }
}

/// Build a file inode with a randomly generated file_id.
pub fn build_file_inode(
    size: u64,
    created: u64,
    modified: u64,
    blocks: Vec<InodeBlock>,
    erasure: InodeErasure,
) -> FileInode {
    let mut file_id_bytes = [0_u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut file_id_bytes);
    build_file_inode_with_id(&file_id_bytes, size, created, modified, blocks, erasure)
}

/// Build a file inode with a caller-provided file_id (32 bytes).
/// Use this when the file_id must match the key derivation used to encrypt blocks.
pub fn build_file_inode_with_id(
    file_id_bytes: &[u8; 32],
    size: u64,
    created: u64,
    modified: u64,
    blocks: Vec<InodeBlock>,
    erasure: InodeErasure,
) -> FileInode {
    FileInode {
        version: 1,
        inode_type: "file".into(),
        size,
        created,
        modified,
        file_id: STANDARD.encode(file_id_bytes),
        blocks,
        erasure,
    }
}

/// Build a file inode from a completed MVP replication upload.
pub fn build_file_inode_from_replication(
    size: u64,
    created: u64,
    content: &[u8],
    upload: &ReplicationUpload,
    servers: &[BlossomServer],
) -> FileInode {
    let content_hash = hex::encode(Sha256::digest(content));
    let shares: Vec<InodeShare> = upload
        .shares
        .iter()
        .map(|s| InodeShare {
            id: s.share_id_hex.clone(),
            server: s.server_url.clone(),
            auth: "blob".into(),
        })
        .collect();

    let block = InodeBlock {
        index: 0,
        hash: content_hash,
        shares,
    };

    build_file_inode(
        size,
        created,
        created,
        vec![block],
        InodeErasure::replicated(servers.len() as u8),
    )
}

/// Build a file inode from a completed erasure-coded upload.
pub fn build_file_inode_from_erasure(
    size: u64,
    created: u64,
    content: &[u8],
    upload: &ErasureReplicationUpload,
) -> FileInode {
    let content_hash = hex::encode(Sha256::digest(content));
    let shares: Vec<InodeShare> = upload
        .shares
        .iter()
        .map(|s| InodeShare {
            id: s.share_id_hex.clone(),
            server: s.server_url.clone(),
            auth: "blob".into(),
        })
        .collect();

    let block = InodeBlock {
        index: 0,
        hash: content_hash,
        shares,
    };

    build_file_inode(
        size,
        created,
        created,
        vec![block],
        InodeErasure::erasure_coded(upload.k as u8, upload.n as u8),
    )
}

/// Encrypt a file inode with the metadata_key and a random nonce.
/// The nonce should be stored in the parent reference (directory entry or
/// commit root_inode field).
pub fn encrypt_inode(
    metadata_key: &[u8; 32],
    inode: &FileInode,
) -> Result<EncryptedInode, InodeError> {
    let json = serde_json::to_vec(inode).map_err(|_| InodeError::Serialization)?;
    let mut nonce = [0_u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let body = encrypt_metadata_block(metadata_key, &nonce, &json)?;
    let hash = hex::encode(Sha256::digest(&body));
    let file_id_bytes = STANDARD
        .decode(&inode.file_id)
        .ok()
        .and_then(|b| <[u8; 32]>::try_from(b.as_slice()).ok())
        .unwrap_or([0_u8; 32]);

    Ok(EncryptedInode {
        body,
        hash,
        nonce,
        file_id_bytes,
    })
}

/// Decrypt a file inode from an encrypted block using the metadata_key and
/// the nonce from the parent reference.
pub fn decrypt_inode(metadata_key: &[u8; 32], encrypted: &[u8]) -> Result<FileInode, InodeError> {
    let plaintext = decrypt_metadata_block(metadata_key, encrypted)
        .map_err(|e| InodeError::Decryption(e.to_string()))?;
    serde_json::from_slice(&plaintext).map_err(|_| InodeError::Deserialization)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::{
        prepare_erasure_coded_upload, prepare_replication_upload, ErasureCodingConfig,
    };
    use crate::key_hierarchy::{derive_master_key, derive_metadata_key};
    use crate::packaging::BLOCK_SIZE;

    fn test_metadata_key() -> [u8; 32] {
        let private_key =
            hex::decode("7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a")
                .unwrap();
        let private_key: [u8; 32] = private_key.try_into().unwrap();
        let master_key = derive_master_key(&private_key).unwrap();
        derive_metadata_key(&master_key).unwrap()
    }

    #[test]
    fn builds_and_serializes_file_inode() {
        let inode = build_file_inode(
            1024,
            1_701_907_200,
            1_701_907_200,
            vec![InodeBlock {
                index: 0,
                hash: "a".repeat(64),
                shares: vec![InodeShare {
                    id: "b".repeat(64),
                    server: "https://blossom1.example".into(),
                    auth: "blob".into(),
                }],
            }],
            InodeErasure::replicated(3),
        );

        assert_eq!(inode.version, 1);
        assert_eq!(inode.inode_type, "file");
        assert_eq!(inode.size, 1024);
        assert_eq!(inode.blocks.len(), 1);
        assert_eq!(inode.erasure.k, 1);
        assert_eq!(inode.erasure.n, 3);

        // file_id should be 32 bytes encoded as base64
        let file_id_bytes = STANDARD.decode(&inode.file_id).unwrap();
        assert_eq!(file_id_bytes.len(), 32);

        // Should serialize to valid JSON
        let json = serde_json::to_string(&inode).unwrap();
        assert!(json.contains("\"type\":\"file\""));
        assert!(json.contains("\"file_id\""));
    }

    #[test]
    fn inode_encrypt_decrypt_round_trip() {
        let metadata_key = test_metadata_key();
        let inode = build_file_inode(
            2048,
            1_701_907_200,
            1_701_907_260,
            vec![InodeBlock {
                index: 0,
                hash: "c".repeat(64),
                shares: vec![
                    InodeShare {
                        id: "d".repeat(64),
                        server: "https://cdn.nostrcheck.me".into(),
                        auth: "blob".into(),
                    },
                    InodeShare {
                        id: "e".repeat(64),
                        server: "https://blossom.nostr.build".into(),
                        auth: "blob".into(),
                    },
                    InodeShare {
                        id: "f".repeat(64),
                        server: "https://blossom.yakihonne.com".into(),
                        auth: "blob".into(),
                    },
                ],
            }],
            InodeErasure::replicated(3),
        );

        let encrypted = encrypt_inode(&metadata_key, &inode).unwrap();
        assert_eq!(encrypted.body.len(), BLOCK_SIZE);
        assert_eq!(encrypted.hash.len(), 64);
        assert_eq!(encrypted.nonce.len(), 12);

        let decrypted = decrypt_inode(&metadata_key, &encrypted.body).unwrap();
        assert_eq!(decrypted, inode);
    }

    #[test]
    fn inode_from_replication_upload() {
        let servers = vec![
            BlossomServer::new("https://cdn.nostrcheck.me"),
            BlossomServer::new("https://blossom.nostr.build"),
            BlossomServer::new("https://blossom.yakihonne.com"),
        ];

        let file_key = [9_u8; 32];
        let nonce = [3_u8; 12];
        let content = b"test inode content";

        let upload = prepare_replication_upload(file_key, 0, nonce, content, &servers).unwrap();
        let inode = build_file_inode_from_replication(
            content.len() as u64,
            1_701_907_200,
            content,
            &upload,
            &servers,
        );

        assert_eq!(inode.size, 18);
        assert_eq!(inode.blocks.len(), 1);
        assert_eq!(inode.blocks[0].shares.len(), 3);
        assert_eq!(inode.erasure.k, 1);
        assert_eq!(inode.erasure.n, 3);
        // All shares should have the same ID (MVP replication)
        assert_eq!(inode.blocks[0].shares[0].id, inode.blocks[0].shares[1].id);
    }

    #[test]
    fn inode_from_erasure_coded_upload() {
        let servers: Vec<BlossomServer> = (0..5)
            .map(|i| BlossomServer::new(&format!("https://blossom{i}.example")))
            .collect();
        let config = ErasureCodingConfig::new(3, 2);
        let file_key = [7_u8; 32];
        let nonce = [5_u8; 12];
        let content = b"erasure coded inode test";

        let upload =
            prepare_erasure_coded_upload(file_key, 0, nonce, content, &servers, &config).unwrap();
        let inode =
            build_file_inode_from_erasure(content.len() as u64, 1_701_907_200, content, &upload);

        assert_eq!(inode.size, 24);
        assert_eq!(inode.blocks.len(), 1);
        assert_eq!(inode.blocks[0].shares.len(), 5);
        assert_eq!(inode.erasure.k, 3);
        assert_eq!(inode.erasure.n, 5);
        // All shares should have distinct IDs (erasure coding)
        let ids: Vec<&str> = inode.blocks[0]
            .shares
            .iter()
            .map(|s| s.id.as_str())
            .collect();
        for i in 0..ids.len() {
            for j in (i + 1)..ids.len() {
                assert_ne!(ids[i], ids[j]);
            }
        }
    }

    #[test]
    fn inode_encrypt_decrypt_preserves_file_id_bytes() {
        let metadata_key = test_metadata_key();
        let inode = build_file_inode(
            512,
            1_701_907_200,
            1_701_907_200,
            vec![],
            InodeErasure::replicated(3),
        );

        let encrypted = encrypt_inode(&metadata_key, &inode).unwrap();
        let file_id_decoded = STANDARD.decode(&inode.file_id).unwrap();
        assert_eq!(encrypted.file_id_bytes, file_id_decoded.as_slice());
    }

    #[test]
    fn inode_decrypt_rejects_wrong_key() {
        let metadata_key = test_metadata_key();
        let inode = build_file_inode(
            256,
            1_701_907_200,
            1_701_907_200,
            vec![],
            InodeErasure::replicated(3),
        );

        let encrypted = encrypt_inode(&metadata_key, &inode).unwrap();

        let wrong_key = [0xFF_u8; 32];
        let result = decrypt_inode(&wrong_key, &encrypted.body);
        assert!(result.is_err());
    }
}
