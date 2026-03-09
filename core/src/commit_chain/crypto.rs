use base64::{engine::general_purpose::STANDARD, Engine as _};
use chacha20::cipher::{KeyIvInit, StreamCipher};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use rand::RngCore;
use sha2::Sha256;

use super::{CommitChainError, CommitPayload};
use crate::packaging::{frame_content, unframe_content, BLOCK_SIZE};

type HmacSha256 = Hmac<Sha256>;
type ChaCha20Cipher = chacha20::ChaCha20;

pub(super) fn decode_private_key(private_key_hex: &str) -> Result<[u8; 32], CommitChainError> {
    let bytes = hex::decode(private_key_hex).map_err(|_| CommitChainError::InvalidPrivateKey)?;
    bytes
        .try_into()
        .map_err(|_| CommitChainError::InvalidPrivateKey)
}

pub(super) fn derive_master_key(
    private_key_hex: &[u8; 32],
    passphrase: &str,
) -> Result<[u8; 32], CommitChainError> {
    let hk = Hkdf::<Sha256>::new(Some(passphrase.as_bytes()), private_key_hex);
    let mut master_key = [0_u8; 32];
    hk.expand(b"garland-v1:master", &mut master_key)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    Ok(master_key)
}

pub(super) fn derive_branch_key(
    master_key: &[u8; 32],
    label: &[u8],
) -> Result<[u8; 32], CommitChainError> {
    let hk = Hkdf::<Sha256>::new(None, master_key);
    let mut branch_key = [0_u8; 32];
    hk.expand(label, &mut branch_key)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    Ok(branch_key)
}

fn split_enc_mac_key(key_material: &[u8; 32]) -> Result<([u8; 32], [u8; 32]), CommitChainError> {
    let hk = Hkdf::<Sha256>::new(None, key_material);
    let mut enc_key = [0_u8; 32];
    let mut mac_key = [0_u8; 32];
    hk.expand(b"garland-v1:enc", &mut enc_key)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    hk.expand(b"garland-v1:mac", &mut mac_key)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    Ok((enc_key, mac_key))
}

pub(super) fn encrypt_metadata_blob(
    metadata_key: &[u8; 32],
    plaintext: &[u8],
) -> Result<Vec<u8>, CommitChainError> {
    let framed = frame_content(plaintext).map_err(|_| CommitChainError::EncryptionFailed)?;
    let mut nonce = [0_u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let (enc_key, mac_key) = split_enc_mac_key(metadata_key)?;
    let mut ciphertext = framed;
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, &nonce)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    cipher.apply_keystream(&mut ciphertext);
    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CommitChainError::EncryptionFailed)?;
    mac.update(&nonce);
    mac.update(&ciphertext);
    let tag = mac.finalize().into_bytes();
    let mut encrypted = Vec::with_capacity(BLOCK_SIZE);
    encrypted.extend_from_slice(&nonce);
    encrypted.extend_from_slice(&ciphertext);
    encrypted.extend_from_slice(&tag);
    Ok(encrypted)
}

pub(super) fn decrypt_metadata_blob(
    metadata_key: &[u8; 32],
    encrypted: &[u8],
) -> Result<Vec<u8>, CommitChainError> {
    if encrypted.len() != BLOCK_SIZE {
        return Err(CommitChainError::DecryptionFailed);
    }
    let nonce: [u8; 12] = encrypted[..12]
        .try_into()
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    let ciphertext = &encrypted[12..BLOCK_SIZE - 32];
    let tag = &encrypted[BLOCK_SIZE - 32..];
    let (enc_key, mac_key) = split_enc_mac_key(metadata_key)?;
    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CommitChainError::DecryptionFailed)?;
    mac.update(&nonce);
    mac.update(ciphertext);
    mac.verify_slice(tag)
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    let mut plaintext = ciphertext.to_vec();
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, &nonce)
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    cipher.apply_keystream(&mut plaintext);
    unframe_content(&plaintext).map_err(|_| CommitChainError::DecryptionFailed)
}

/// Result of encrypting a commit payload. The nonce is returned separately so
/// it can be stored in a `nonce` tag on the Nostr event.
pub(super) struct EncryptedCommitPayload {
    /// Base64-encoded `ciphertext || HMAC-SHA256 tag` (nonce NOT embedded).
    pub content_b64: String,
    /// The 12-byte random nonce used for encryption.
    pub nonce: [u8; 12],
}

pub(super) fn encrypt_commit_payload(
    commit_key: &[u8; 32],
    payload: &CommitPayload,
) -> Result<EncryptedCommitPayload, CommitChainError> {
    let plaintext =
        serde_json::to_vec(payload).map_err(|_| CommitChainError::CommitSerialization)?;
    let mut nonce = [0_u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let (enc_key, mac_key) = split_enc_mac_key(commit_key)?;
    let mut ciphertext = plaintext;
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, &nonce)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    cipher.apply_keystream(&mut ciphertext);
    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CommitChainError::EncryptionFailed)?;
    mac.update(&nonce);
    mac.update(&ciphertext);
    let tag = mac.finalize().into_bytes();
    // New format: content is ciphertext || tag (no embedded nonce).
    // The nonce is stored externally in a Nostr event tag.
    let mut encoded = Vec::with_capacity(ciphertext.len() + 32);
    encoded.extend_from_slice(&ciphertext);
    encoded.extend_from_slice(&tag);
    Ok(EncryptedCommitPayload {
        content_b64: STANDARD.encode(encoded),
        nonce,
    })
}

/// Decrypt a commit payload. If `external_nonce` is provided (from a `nonce`
/// event tag), it is used and the content is `ciphertext || tag`. Otherwise,
/// the legacy format `nonce || ciphertext || tag` is assumed.
pub(super) fn decrypt_commit_payload(
    commit_key: &[u8; 32],
    encoded: &str,
    external_nonce: Option<&[u8; 12]>,
) -> Result<CommitPayload, CommitChainError> {
    let payload = STANDARD
        .decode(encoded)
        .map_err(|_| CommitChainError::DecryptionFailed)?;

    let (nonce, ciphertext, tag) = match external_nonce {
        Some(n) => {
            // New format: nonce from event tag, content = ciphertext || tag
            if payload.len() < 32 {
                return Err(CommitChainError::DecryptionFailed);
            }
            (
                *n,
                &payload[..payload.len() - 32],
                &payload[payload.len() - 32..],
            )
        }
        None => {
            // Legacy format: nonce || ciphertext || tag embedded in content
            if payload.len() < 12 + 32 {
                return Err(CommitChainError::DecryptionFailed);
            }
            let n: [u8; 12] = payload[..12]
                .try_into()
                .map_err(|_| CommitChainError::DecryptionFailed)?;
            (
                n,
                &payload[12..payload.len() - 32],
                &payload[payload.len() - 32..],
            )
        }
    };

    let (enc_key, mac_key) = split_enc_mac_key(commit_key)?;
    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CommitChainError::DecryptionFailed)?;
    mac.update(&nonce);
    mac.update(ciphertext);
    mac.verify_slice(tag)
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    let mut plaintext = ciphertext.to_vec();
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, &nonce)
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    cipher.apply_keystream(&mut plaintext);
    serde_json::from_slice(&plaintext).map_err(|_| CommitChainError::DecryptionFailed)
}
