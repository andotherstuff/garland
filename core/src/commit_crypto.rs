use base64::{engine::general_purpose::STANDARD, Engine as _};
use chacha20::cipher::{KeyIvInit, StreamCipher};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use thiserror::Error;

use crate::key_hierarchy::{derive_commit_key, derive_master_key};

type HmacSha256 = Hmac<Sha256>;
type ChaCha20Cipher = chacha20::ChaCha20;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CommitContentEnvelope {
    pub version: u32,
    pub algorithm: String,
    pub ciphertext_b64: String,
}

#[derive(Debug, Error)]
pub enum CommitCryptoError {
    #[error("private key hex is invalid")]
    InvalidPrivateKey,
    #[error("commit payload is invalid base64")]
    InvalidPayloadBase64,
    #[error("commit payload is truncated")]
    InvalidPayloadLength,
    #[error("commit payload authentication failed")]
    AuthenticationFailed,
    #[error("hkdf expansion failed")]
    HkdfExpansionFailed,
    #[error("commit content envelope is invalid")]
    InvalidEnvelope,
}

pub fn encode_commit_content(encrypted_payload: &[u8]) -> Result<String, CommitCryptoError> {
    serde_json::to_string(&CommitContentEnvelope {
        version: 1,
        algorithm: "chacha20-hmac-sha256".into(),
        ciphertext_b64: STANDARD.encode(encrypted_payload),
    })
    .map_err(|_| CommitCryptoError::InvalidEnvelope)
}

pub fn decode_commit_content(content: &str) -> Result<Vec<u8>, CommitCryptoError> {
    let envelope: CommitContentEnvelope =
        serde_json::from_str(content).map_err(|_| CommitCryptoError::InvalidEnvelope)?;
    if envelope.version != 1 || envelope.algorithm != "chacha20-hmac-sha256" {
        return Err(CommitCryptoError::InvalidEnvelope);
    }
    STANDARD
        .decode(envelope.ciphertext_b64)
        .map_err(|_| CommitCryptoError::InvalidPayloadBase64)
}

pub fn encrypt_commit_payload(
    private_key_hex: &str,
    plaintext: &[u8],
) -> Result<Vec<u8>, CommitCryptoError> {
    let private_key = decode_private_key(private_key_hex)?;
    let (enc_key, mac_key) = derive_commit_keys(&private_key)?;
    let mut nonce = [0_u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);

    let mut ciphertext = plaintext.to_vec();
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, &nonce)
        .map_err(|_| CommitCryptoError::InvalidPayloadLength)?;
    cipher.apply_keystream(&mut ciphertext);

    let mut mac = HmacSha256::new_from_slice(&mac_key)
        .map_err(|_| CommitCryptoError::AuthenticationFailed)?;
    mac.update(&nonce);
    mac.update(&ciphertext);
    let tag = mac.finalize().into_bytes();

    let mut encrypted = Vec::with_capacity(12 + ciphertext.len() + tag.len());
    encrypted.extend_from_slice(&nonce);
    encrypted.extend_from_slice(&ciphertext);
    encrypted.extend_from_slice(&tag);
    Ok(encrypted)
}

pub fn decrypt_commit_payload(
    private_key_hex: &str,
    encrypted: &[u8],
) -> Result<Vec<u8>, CommitCryptoError> {
    if encrypted.len() < 44 {
        return Err(CommitCryptoError::InvalidPayloadLength);
    }

    let private_key = decode_private_key(private_key_hex)?;
    let (enc_key, mac_key) = derive_commit_keys(&private_key)?;
    let nonce = &encrypted[..12];
    let ciphertext = &encrypted[12..encrypted.len() - 32];
    let tag = &encrypted[encrypted.len() - 32..];

    let mut mac = HmacSha256::new_from_slice(&mac_key)
        .map_err(|_| CommitCryptoError::AuthenticationFailed)?;
    mac.update(nonce);
    mac.update(ciphertext);
    mac.verify_slice(tag)
        .map_err(|_| CommitCryptoError::AuthenticationFailed)?;

    let mut plaintext = ciphertext.to_vec();
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, nonce)
        .map_err(|_| CommitCryptoError::InvalidPayloadLength)?;
    cipher.apply_keystream(&mut plaintext);
    Ok(plaintext)
}

fn decode_private_key(private_key_hex: &str) -> Result<[u8; 32], CommitCryptoError> {
    let bytes = hex::decode(private_key_hex).map_err(|_| CommitCryptoError::InvalidPrivateKey)?;
    bytes
        .try_into()
        .map_err(|_| CommitCryptoError::InvalidPrivateKey)
}

fn derive_commit_keys(private_key: &[u8; 32]) -> Result<([u8; 32], [u8; 32]), CommitCryptoError> {
    let master_key =
        derive_master_key(private_key).map_err(|_| CommitCryptoError::HkdfExpansionFailed)?;
    let commit_key =
        derive_commit_key(&master_key).map_err(|_| CommitCryptoError::HkdfExpansionFailed)?;

    let commit_hk = Hkdf::<Sha256>::from_prk(&commit_key)
        .map_err(|_| CommitCryptoError::HkdfExpansionFailed)?;
    let mut enc_key = [0_u8; 32];
    let mut mac_key = [0_u8; 32];
    commit_hk
        .expand(b"garland-v1:enc", &mut enc_key)
        .map_err(|_| CommitCryptoError::HkdfExpansionFailed)?;
    commit_hk
        .expand(b"garland-v1:mac", &mut mac_key)
        .map_err(|_| CommitCryptoError::HkdfExpansionFailed)?;
    Ok((enc_key, mac_key))
}
