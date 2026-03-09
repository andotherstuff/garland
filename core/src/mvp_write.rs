use base64::{engine::general_purpose::STANDARD, Engine as _};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::commit_crypto::{encode_commit_content, encrypt_commit_payload};
use crate::crypto::{decrypt_block, prepare_replication_upload, BlossomServer};
use crate::key_hierarchy::{derive_file_key as derive_file_key_from_master, derive_master_key};
use crate::nostr_event::{sign_custom_event, SignedEvent, UnsignedEvent};
use crate::packaging::CONTENT_CAPACITY;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrepareWriteRequest {
    pub private_key_hex: String,
    pub display_name: String,
    pub mime_type: String,
    pub created_at: u64,
    pub content_b64: String,
    pub servers: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub document_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub previous_event_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RecoverReadRequest {
    pub private_key_hex: String,
    pub document_id: String,
    pub block_index: u32,
    pub encrypted_block_b64: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ManifestBlock {
    pub index: u32,
    pub nonce_b64: String,
    pub share_id_hex: String,
    pub servers: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct WriteManifest {
    pub version: u32,
    pub document_id: String,
    pub display_name: String,
    pub mime_type: String,
    pub size_bytes: usize,
    pub sha256_hex: String,
    pub created_at: u64,
    pub blocks: Vec<ManifestBlock>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct UploadInstruction {
    pub server_url: String,
    pub share_id_hex: String,
    pub body_b64: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct PreparedWritePlan {
    pub document_id: String,
    pub manifest: WriteManifest,
    pub uploads: Vec<UploadInstruction>,
    pub commit_event: SignedEvent,
}

#[derive(Debug, Error)]
pub enum WritePlanError {
    #[error("content is not valid base64")]
    InvalidContentBase64,
    #[error("private key hex is invalid")]
    InvalidPrivateKey,
    #[error("document ID is invalid")]
    InvalidDocumentId,
    #[error("crypto step failed: {0}")]
    Crypto(String),
    #[error("nostr event signing failed: {0}")]
    EventSigning(String),
    #[error("manifest serialization failed")]
    ManifestSerialization,
    #[error("commit payload encryption failed: {0}")]
    CommitEncryption(String),
}

#[derive(Debug, Error)]
pub enum ReadRecoveryError {
    #[error("encrypted block is not valid base64")]
    InvalidBlockBase64,
    #[error("private key hex is invalid")]
    InvalidPrivateKey,
    #[error("crypto step failed: {0}")]
    Crypto(String),
}

pub fn prepare_single_block_write(
    request: &PrepareWriteRequest,
) -> Result<PreparedWritePlan, WritePlanError> {
    let content = STANDARD
        .decode(&request.content_b64)
        .map_err(|_| WritePlanError::InvalidContentBase64)?;

    let private_key_bytes = decode_private_key(&request.private_key_hex)?;
    let document_id = match request.document_id.as_deref() {
        Some(document_id) => {
            decode_document_id(document_id).map_err(|_| WritePlanError::InvalidDocumentId)?;
            document_id.to_owned()
        }
        None => random_document_id_hex(),
    };
    let file_key = derive_file_key(&private_key_bytes, &document_id)?;
    let nonce = random_nonce();
    let servers: Vec<BlossomServer> = request
        .servers
        .iter()
        .map(|url| BlossomServer::new(url))
        .collect();
    let block_chunks = split_into_blocks(&content);
    let mut manifest_blocks = Vec::with_capacity(block_chunks.len());
    let mut uploads = Vec::with_capacity(block_chunks.len() * request.servers.len());

    for (block_index, chunk) in block_chunks.iter().enumerate() {
        let nonce = if block_index == 0 {
            nonce
        } else {
            random_nonce()
        };
        let replication =
            prepare_replication_upload(file_key, block_index as u32, nonce, chunk, &servers)
                .map_err(|err| WritePlanError::Crypto(err.to_string()))?;
        let share_id_hex = replication
            .shares
            .first()
            .map(|share| share.share_id_hex.clone())
            .unwrap_or_default();

        manifest_blocks.push(ManifestBlock {
            index: block_index as u32,
            nonce_b64: STANDARD.encode(nonce),
            share_id_hex,
            servers: request.servers.clone(),
        });

        uploads.extend(
            replication
                .shares
                .into_iter()
                .map(|share| UploadInstruction {
                    server_url: share.server_url,
                    share_id_hex: share.share_id_hex,
                    body_b64: STANDARD.encode(share.body),
                }),
        );
    }

    let manifest = WriteManifest {
        version: 1,
        document_id: document_id.clone(),
        display_name: request.display_name.clone(),
        mime_type: request.mime_type.clone(),
        size_bytes: content.len(),
        sha256_hex: hex::encode(Sha256::digest(&content)),
        created_at: request.created_at,
        blocks: manifest_blocks,
    };
    let manifest_json =
        serde_json::to_vec(&manifest).map_err(|_| WritePlanError::ManifestSerialization)?;
    let encrypted_commit_payload = encrypt_commit_payload(&request.private_key_hex, &manifest_json)
        .map_err(|err| WritePlanError::CommitEncryption(err.to_string()))?;
    let commit_content = encode_commit_content(&encrypted_commit_payload)
        .map_err(|err| WritePlanError::CommitEncryption(err.to_string()))?;
    let mut tags: Vec<Vec<String>> = vec![vec!["d".into(), document_id.clone()]];
    if let Some(ref prev_id) = request.previous_event_id {
        tags.push(vec!["e".into(), prev_id.clone()]);
    }

    let commit_event = sign_custom_event(
        &request.private_key_hex,
        &UnsignedEvent {
            created_at: request.created_at,
            kind: 1097,
            tags,
            content: commit_content,
        },
    )
    .map_err(|err| WritePlanError::EventSigning(err.to_string()))?;
    Ok(PreparedWritePlan {
        document_id,
        manifest,
        uploads,
        commit_event,
    })
}

fn random_document_id_hex() -> String {
    let mut document_id = [0_u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut document_id);
    hex::encode(document_id)
}

fn split_into_blocks(content: &[u8]) -> Vec<&[u8]> {
    if content.is_empty() {
        return vec![content];
    }

    content.chunks(CONTENT_CAPACITY).collect()
}

pub fn recover_single_block_read(
    request: &RecoverReadRequest,
) -> Result<Vec<u8>, ReadRecoveryError> {
    let encrypted_block = STANDARD
        .decode(&request.encrypted_block_b64)
        .map_err(|_| ReadRecoveryError::InvalidBlockBase64)?;
    let private_key_bytes = decode_private_key_read(&request.private_key_hex)?;
    let file_key = derive_file_key_read(&private_key_bytes, &request.document_id)?;

    decrypt_block(&file_key, request.block_index, &encrypted_block)
        .map_err(|err| ReadRecoveryError::Crypto(err.to_string()))
}

fn decode_private_key(private_key_hex: &str) -> Result<[u8; 32], WritePlanError> {
    let bytes = hex::decode(private_key_hex).map_err(|_| WritePlanError::InvalidPrivateKey)?;
    bytes
        .try_into()
        .map_err(|_| WritePlanError::InvalidPrivateKey)
}

fn decode_private_key_read(private_key_hex: &str) -> Result<[u8; 32], ReadRecoveryError> {
    let bytes = hex::decode(private_key_hex).map_err(|_| ReadRecoveryError::InvalidPrivateKey)?;
    bytes
        .try_into()
        .map_err(|_| ReadRecoveryError::InvalidPrivateKey)
}

fn derive_file_key(private_key: &[u8; 32], document_id: &str) -> Result<[u8; 32], WritePlanError> {
    let document_id_bytes =
        decode_document_id(document_id).map_err(|_| WritePlanError::InvalidDocumentId)?;
    let master_key =
        derive_master_key(private_key).map_err(|err| WritePlanError::Crypto(err.to_string()))?;
    derive_file_key_from_master(&master_key, &document_id_bytes)
        .map_err(|err| WritePlanError::Crypto(err.to_string()))
}

fn derive_file_key_read(
    private_key: &[u8; 32],
    document_id: &str,
) -> Result<[u8; 32], ReadRecoveryError> {
    let document_id_bytes =
        decode_document_id(document_id).map_err(|_| ReadRecoveryError::InvalidPrivateKey)?;
    let master_key =
        derive_master_key(private_key).map_err(|err| ReadRecoveryError::Crypto(err.to_string()))?;
    derive_file_key_from_master(&master_key, &document_id_bytes)
        .map_err(|err| ReadRecoveryError::Crypto(err.to_string()))
}

fn decode_document_id(document_id: &str) -> Result<[u8; 32], hex::FromHexError> {
    let bytes = hex::decode(document_id)?;
    let array: [u8; 32] = bytes
        .try_into()
        .map_err(|_| hex::FromHexError::InvalidStringLength)?;
    Ok(array)
}

fn random_nonce() -> [u8; 12] {
    let mut nonce = [0_u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    nonce
}
