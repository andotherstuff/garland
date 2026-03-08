use chacha20::cipher::{KeyIvInit, StreamCipher};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::packaging::{BLOCK_SIZE, frame_content, unframe_content};

type HmacSha256 = Hmac<Sha256>;
type ChaCha20Cipher = chacha20::ChaCha20;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlossomServer {
    pub server_url: String,
}

impl BlossomServer {
    pub fn new(server_url: &str) -> Self {
        Self {
            server_url: server_url.to_owned(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PreparedShare {
    pub server_url: String,
    pub share_id_hex: String,
    pub body: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReplicationUpload {
    pub share_size: usize,
    pub shares: Vec<PreparedShare>,
}

pub const REPLICATION_FACTOR: usize = 3;

#[derive(Debug, Error)]
pub enum CryptoError {
    #[error("content packaging failed: {0}")]
    Packaging(String),
    #[error("encrypted block length is invalid")]
    InvalidBlockLength,
    #[error("block authentication failed")]
    AuthenticationFailed,
    #[error("hkdf expansion failed")]
    HkdfExpansionFailed,
    #[error("replication upload requires exactly three servers in MVP mode")]
    InvalidReplicationSet,
}

pub fn encrypt_block(
    file_key: &[u8; 32],
    block_index: u32,
    nonce: &[u8; 12],
    content: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let plaintext =
        frame_content(content).map_err(|err| CryptoError::Packaging(err.to_string()))?;
    let (enc_key, mac_key) = derive_block_keys(file_key, block_index)?;

    let mut ciphertext = plaintext;
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, nonce)
        .map_err(|_| CryptoError::InvalidBlockLength)?;
    cipher.apply_keystream(&mut ciphertext);

    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CryptoError::AuthenticationFailed)?;
    mac.update(nonce);
    mac.update(&ciphertext);
    let tag = mac.finalize().into_bytes();

    let mut encrypted = Vec::with_capacity(BLOCK_SIZE);
    encrypted.extend_from_slice(nonce);
    encrypted.extend_from_slice(&ciphertext);
    encrypted.extend_from_slice(&tag);
    Ok(encrypted)
}

pub fn decrypt_block(
    file_key: &[u8; 32],
    block_index: u32,
    encrypted_block: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    if encrypted_block.len() != BLOCK_SIZE {
        return Err(CryptoError::InvalidBlockLength);
    }

    let nonce: [u8; 12] = encrypted_block[..12]
        .try_into()
        .map_err(|_| CryptoError::InvalidBlockLength)?;
    let ciphertext = &encrypted_block[12..BLOCK_SIZE - 32];
    let tag = &encrypted_block[BLOCK_SIZE - 32..];
    let (enc_key, mac_key) = derive_block_keys(file_key, block_index)?;

    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CryptoError::AuthenticationFailed)?;
    mac.update(&nonce);
    mac.update(ciphertext);
    mac.verify_slice(tag)
        .map_err(|_| CryptoError::AuthenticationFailed)?;

    let mut plaintext = ciphertext.to_vec();
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, &nonce)
        .map_err(|_| CryptoError::InvalidBlockLength)?;
    cipher.apply_keystream(&mut plaintext);

    unframe_content(&plaintext).map_err(|err| CryptoError::Packaging(err.to_string()))
}

pub fn prepare_replication_upload(
    file_key: [u8; 32],
    block_index: u32,
    nonce: [u8; 12],
    content: &[u8],
    servers: &[BlossomServer],
) -> Result<ReplicationUpload, CryptoError> {
    if servers.len() != REPLICATION_FACTOR {
        return Err(CryptoError::InvalidReplicationSet);
    }

    let body = encrypt_block(&file_key, block_index, &nonce, content)?;
    let share_id_hex = hex::encode(Sha256::digest(&body));
    let shares = servers
        .iter()
        .map(|server| PreparedShare {
            server_url: server.server_url.clone(),
            share_id_hex: share_id_hex.clone(),
            body: body.clone(),
        })
        .collect();

    Ok(ReplicationUpload {
        share_size: body.len(),
        shares,
    })
}

pub fn prepare_metadata_replication_upload(
    metadata_key: [u8; 32],
    nonce: [u8; 12],
    content: &[u8],
    servers: &[BlossomServer],
) -> Result<ReplicationUpload, CryptoError> {
    if servers.len() != REPLICATION_FACTOR {
        return Err(CryptoError::InvalidReplicationSet);
    }

    let body = encrypt_metadata_block(&metadata_key, &nonce, content)?;
    let share_id_hex = hex::encode(Sha256::digest(&body));
    let shares = servers
        .iter()
        .map(|server| PreparedShare {
            server_url: server.server_url.clone(),
            share_id_hex: share_id_hex.clone(),
            body: body.clone(),
        })
        .collect();

    Ok(ReplicationUpload {
        share_size: body.len(),
        shares,
    })
}

pub fn encrypt_metadata_block(
    metadata_key: &[u8; 32],
    nonce: &[u8; 12],
    content: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let plaintext =
        frame_content(content).map_err(|err| CryptoError::Packaging(err.to_string()))?;
    let (enc_key, mac_key) = derive_direct_keys(metadata_key)?;

    let mut ciphertext = plaintext;
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, nonce)
        .map_err(|_| CryptoError::InvalidBlockLength)?;
    cipher.apply_keystream(&mut ciphertext);

    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CryptoError::AuthenticationFailed)?;
    mac.update(nonce);
    mac.update(&ciphertext);
    let tag = mac.finalize().into_bytes();

    let mut encrypted = Vec::with_capacity(BLOCK_SIZE);
    encrypted.extend_from_slice(nonce);
    encrypted.extend_from_slice(&ciphertext);
    encrypted.extend_from_slice(&tag);
    Ok(encrypted)
}

pub fn decrypt_metadata_block(
    metadata_key: &[u8; 32],
    encrypted_block: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    if encrypted_block.len() != BLOCK_SIZE {
        return Err(CryptoError::InvalidBlockLength);
    }

    let nonce: [u8; 12] = encrypted_block[..12]
        .try_into()
        .map_err(|_| CryptoError::InvalidBlockLength)?;
    let ciphertext = &encrypted_block[12..BLOCK_SIZE - 32];
    let tag = &encrypted_block[BLOCK_SIZE - 32..];
    let (enc_key, mac_key) = derive_direct_keys(metadata_key)?;

    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CryptoError::AuthenticationFailed)?;
    mac.update(&nonce);
    mac.update(ciphertext);
    mac.verify_slice(tag)
        .map_err(|_| CryptoError::AuthenticationFailed)?;

    let mut plaintext = ciphertext.to_vec();
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, &nonce)
        .map_err(|_| CryptoError::InvalidBlockLength)?;
    cipher.apply_keystream(&mut plaintext);

    unframe_content(&plaintext).map_err(|err| CryptoError::Packaging(err.to_string()))
}

fn derive_block_keys(
    file_key: &[u8; 32],
    block_index: u32,
) -> Result<([u8; 32], [u8; 32]), CryptoError> {
    let hk = Hkdf::<Sha256>::from_prk(file_key).map_err(|_| CryptoError::HkdfExpansionFailed)?;
    let mut block_key = [0_u8; 32];

    let mut block_info = Vec::with_capacity(b"garland-v1:block:".len() + 8);
    block_info.extend_from_slice(b"garland-v1:block:");
    block_info.extend_from_slice(&(block_index as u64).to_be_bytes());
    hk.expand(&block_info, &mut block_key)
        .map_err(|_| CryptoError::HkdfExpansionFailed)?;

    derive_direct_keys(&block_key)
}

fn derive_direct_keys(base_key: &[u8; 32]) -> Result<([u8; 32], [u8; 32]), CryptoError> {
    let hk = Hkdf::<Sha256>::from_prk(base_key).map_err(|_| CryptoError::HkdfExpansionFailed)?;
    let mut enc_key = [0_u8; 32];
    let mut mac_key = [0_u8; 32];
    hk.expand(b"garland-v1:enc", &mut enc_key)
        .map_err(|_| CryptoError::HkdfExpansionFailed)?;
    hk.expand(b"garland-v1:mac", &mut mac_key)
        .map_err(|_| CryptoError::HkdfExpansionFailed)?;
    Ok((enc_key, mac_key))
}
