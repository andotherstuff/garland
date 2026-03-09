use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use nostr::nips::nip06::FromMnemonic;
use nostr::nips::nip19::ToBech32;
use nostr::{Keys, SecretKey};
use pbkdf2::pbkdf2_hmac;
use serde::Serialize;
use sha2::Sha256;
use thiserror::Error;

type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct NostrIdentity {
    pub private_key_hex: String,
    pub nsec: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct GeneratedIdentity {
    pub mnemonic: String,
    pub private_key_hex: String,
    pub nsec: String,
}

#[derive(Debug, Error)]
pub enum IdentityError {
    #[error("mnemonic is invalid: {0}")]
    InvalidMnemonic(String),
    #[error("storage identity derivation failed")]
    StorageIdentityDerivation,
    #[error("bech32 encoding failed")]
    Bech32Encoding,
    #[error("mnemonic generation failed")]
    MnemonicGeneration,
}

pub fn generate_identity(passphrase: &str) -> Result<GeneratedIdentity, IdentityError> {
    let mut entropy = [0_u8; 16]; // 128 bits = 12-word mnemonic
    rand::Rng::fill(&mut rand::thread_rng(), &mut entropy);
    let mnemonic = bip39::Mnemonic::from_entropy(&entropy)
        .map_err(|_| IdentityError::MnemonicGeneration)?;
    let mnemonic_str = mnemonic.to_string();
    let identity = derive_nostr_identity(&mnemonic_str, passphrase)?;
    Ok(GeneratedIdentity {
        mnemonic: mnemonic_str,
        private_key_hex: identity.private_key_hex,
        nsec: identity.nsec,
    })
}

pub fn derive_nostr_identity(
    mnemonic: &str,
    passphrase: &str,
) -> Result<NostrIdentity, IdentityError> {
    let keys = Keys::from_mnemonic_advanced(mnemonic, Some(""), Some(0), Some(0), Some(0))
        .map_err(|err| IdentityError::InvalidMnemonic(err.to_string()))?;
    let base_private_key = keys.secret_key().secret_bytes();
    let storage_secret_key = derive_storage_secret_key(&base_private_key, passphrase)?;
    let private_key_hex = storage_secret_key.to_secret_hex();
    let nsec = storage_secret_key
        .to_bech32()
        .map_err(|_| IdentityError::Bech32Encoding)?;

    Ok(NostrIdentity {
        private_key_hex,
        nsec,
    })
}

fn derive_storage_secret_key(
    base_private_key: &[u8; 32],
    passphrase: &str,
) -> Result<SecretKey, IdentityError> {
    let salt = keyed_hmac(b"garland-v1-salt", base_private_key)?;
    let mut stretched = [0_u8; 32];
    pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), &salt, 210_000, &mut stretched);

    let mut seed_input = Vec::with_capacity(base_private_key.len() + stretched.len());
    seed_input.extend_from_slice(base_private_key);
    seed_input.extend_from_slice(&stretched);
    let seed = keyed_hmac(b"garland-v1-nsec", &seed_input)?;

    derive_secp256k1_scalar(&seed, b"garland-v1:storage-scalar")
}

fn keyed_hmac(key: &[u8], message: &[u8]) -> Result<[u8; 32], IdentityError> {
    let mut mac =
        HmacSha256::new_from_slice(key).map_err(|_| IdentityError::StorageIdentityDerivation)?;
    mac.update(message);
    let output = mac.finalize().into_bytes();
    let mut bytes = [0_u8; 32];
    bytes.copy_from_slice(&output);
    Ok(bytes)
}

fn derive_secp256k1_scalar(prk: &[u8; 32], info: &[u8]) -> Result<SecretKey, IdentityError> {
    let hk = Hkdf::<Sha256>::from_prk(prk).map_err(|_| IdentityError::StorageIdentityDerivation)?;
    let mut counter = 0_u32;

    loop {
        let mut candidate = [0_u8; 32];
        let mut candidate_info = Vec::with_capacity(info.len() + 4);
        candidate_info.extend_from_slice(info);
        candidate_info.extend_from_slice(&counter.to_be_bytes());
        hk.expand(&candidate_info, &mut candidate)
            .map_err(|_| IdentityError::StorageIdentityDerivation)?;
        if let Ok(secret_key) = SecretKey::from_slice(&candidate) {
            return Ok(secret_key);
        }
        counter = counter
            .checked_add(1)
            .ok_or(IdentityError::StorageIdentityDerivation)?;
    }
}
