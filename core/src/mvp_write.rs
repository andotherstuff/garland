use base64::{engine::general_purpose::STANDARD, Engine as _};
use hkdf::Hkdf;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::crypto::{decrypt_block, prepare_replication_upload, BlossomServer};
use crate::nostr_event::{sign_custom_event, SignedEvent, UnsignedEvent};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrepareWriteRequest {
    pub private_key_hex: String,
    pub display_name: String,
    pub mime_type: String,
    pub created_at: u64,
    pub content_b64: String,
    pub servers: Vec<String>,
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

#[derive(Debug, Clone, Serialize)]
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
    #[error("crypto step failed: {0}")]
    Crypto(String),
    #[error("nostr event signing failed: {0}")]
    EventSigning(String),
    #[error("manifest serialization failed")]
    ManifestSerialization,
    #[error("content length exceeds single-block MVP limit")]
    ContentTooLarge,
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

    if content.len() > 262_096 {
        return Err(WritePlanError::ContentTooLarge);
    }

    let private_key_bytes = decode_private_key(&request.private_key_hex)?;
    let document_id = hex::encode(Sha256::digest(
        format!(
            "{}\n{}\n{}",
            request.display_name,
            request.created_at,
            hex::encode(Sha256::digest(&content))
        )
        .as_bytes(),
    ));
    let file_key = derive_file_key(&private_key_bytes, &document_id)?;
    let nonce = random_nonce();
    let servers: Vec<BlossomServer> = request
        .servers
        .iter()
        .map(|url| BlossomServer::new(url))
        .collect();
    let replication = prepare_replication_upload(file_key, 0, nonce, &content, &servers)
        .map_err(|err| WritePlanError::Crypto(err.to_string()))?;

    let share_id_hex = replication
        .shares
        .first()
        .map(|share| share.share_id_hex.clone())
        .unwrap_or_default();
    let manifest = WriteManifest {
        version: 1,
        document_id: document_id.clone(),
        display_name: request.display_name.clone(),
        mime_type: request.mime_type.clone(),
        size_bytes: content.len(),
        sha256_hex: hex::encode(Sha256::digest(&content)),
        created_at: request.created_at,
        blocks: vec![ManifestBlock {
            index: 0,
            nonce_b64: STANDARD.encode(nonce),
            share_id_hex: share_id_hex.clone(),
            servers: request.servers.clone(),
        }],
    };
    let manifest_json =
        serde_json::to_string(&manifest).map_err(|_| WritePlanError::ManifestSerialization)?;
    let commit_event = sign_custom_event(
        &request.private_key_hex,
        &UnsignedEvent {
            created_at: request.created_at,
            kind: 1097,
            tags: vec![],
            content: STANDARD.encode(manifest_json.as_bytes()),
        },
    )
    .map_err(|err| WritePlanError::EventSigning(err.to_string()))?;

    let uploads = replication
        .shares
        .into_iter()
        .map(|share| UploadInstruction {
            server_url: share.server_url,
            share_id_hex: share.share_id_hex,
            body_b64: STANDARD.encode(share.body),
        })
        .collect();

    Ok(PreparedWritePlan {
        document_id,
        manifest,
        uploads,
        commit_event,
    })
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
    let hk = Hkdf::<Sha256>::new(None, private_key);
    let mut file_key = [0_u8; 32];
    hk.expand(
        format!("garland-mvp:file:{}", document_id).as_bytes(),
        &mut file_key,
    )
    .map_err(|err| WritePlanError::Crypto(err.to_string()))?;
    Ok(file_key)
}

fn derive_file_key_read(
    private_key: &[u8; 32],
    document_id: &str,
) -> Result<[u8; 32], ReadRecoveryError> {
    let hk = Hkdf::<Sha256>::new(None, private_key);
    let mut file_key = [0_u8; 32];
    hk.expand(
        format!("garland-mvp:file:{}", document_id).as_bytes(),
        &mut file_key,
    )
    .map_err(|err| ReadRecoveryError::Crypto(err.to_string()))?;
    Ok(file_key)
}

fn random_nonce() -> [u8; 12] {
    let mut nonce = [0_u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    nonce
}
