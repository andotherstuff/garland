use hkdf::Hkdf;
use nostr::SecretKey;
use thiserror::Error;

use sha2::Sha256;

#[derive(Debug, Error)]
pub enum KeyHierarchyError {
    #[error("hkdf expansion failed")]
    HkdfExpansionFailed,
    #[error("secp256k1 scalar derivation failed")]
    Secp256k1ScalarDerivationFailed,
}

pub fn derive_master_key(storage_private_key: &[u8; 32]) -> Result<[u8; 32], KeyHierarchyError> {
    let hk = Hkdf::<Sha256>::new(None, storage_private_key);
    let mut master_key = [0_u8; 32];
    hk.expand(b"garland-v1:master", &mut master_key)
        .map_err(|_| KeyHierarchyError::HkdfExpansionFailed)?;
    Ok(master_key)
}

pub fn derive_commit_key(master_key: &[u8; 32]) -> Result<[u8; 32], KeyHierarchyError> {
    let hk =
        Hkdf::<Sha256>::from_prk(master_key).map_err(|_| KeyHierarchyError::HkdfExpansionFailed)?;
    let mut commit_key = [0_u8; 32];
    hk.expand(b"garland-v1:commit", &mut commit_key)
        .map_err(|_| KeyHierarchyError::HkdfExpansionFailed)?;
    Ok(commit_key)
}

pub fn derive_metadata_key(master_key: &[u8; 32]) -> Result<[u8; 32], KeyHierarchyError> {
    let hk =
        Hkdf::<Sha256>::from_prk(master_key).map_err(|_| KeyHierarchyError::HkdfExpansionFailed)?;
    let mut metadata_key = [0_u8; 32];
    hk.expand(b"garland-v1:metadata", &mut metadata_key)
        .map_err(|_| KeyHierarchyError::HkdfExpansionFailed)?;
    Ok(metadata_key)
}

pub fn derive_file_key(
    master_key: &[u8; 32],
    file_id: &[u8; 32],
) -> Result<[u8; 32], KeyHierarchyError> {
    let hk =
        Hkdf::<Sha256>::from_prk(master_key).map_err(|_| KeyHierarchyError::HkdfExpansionFailed)?;
    let mut file_key = [0_u8; 32];
    let mut info = Vec::with_capacity(b"garland-v1:file:".len() + file_id.len());
    info.extend_from_slice(b"garland-v1:file:");
    info.extend_from_slice(file_id);
    hk.expand(&info, &mut file_key)
        .map_err(|_| KeyHierarchyError::HkdfExpansionFailed)?;
    Ok(file_key)
}

pub fn derive_blob_auth_private_key(
    master_key: &[u8; 32],
    share_id: &[u8; 32],
) -> Result<[u8; 32], KeyHierarchyError> {
    let mut info = Vec::with_capacity(b"garland-v1:auth:".len() + share_id.len());
    info.extend_from_slice(b"garland-v1:auth:");
    info.extend_from_slice(share_id);
    derive_secp256k1_scalar(master_key, &info)
}

fn derive_secp256k1_scalar(prk: &[u8; 32], info: &[u8]) -> Result<[u8; 32], KeyHierarchyError> {
    let hk = Hkdf::<Sha256>::from_prk(prk).map_err(|_| KeyHierarchyError::HkdfExpansionFailed)?;
    let mut counter = 0_u32;

    loop {
        let mut candidate = [0_u8; 32];
        let mut candidate_info = Vec::with_capacity(info.len() + 4);
        candidate_info.extend_from_slice(info);
        candidate_info.extend_from_slice(&counter.to_be_bytes());
        hk.expand(&candidate_info, &mut candidate)
            .map_err(|_| KeyHierarchyError::HkdfExpansionFailed)?;
        if SecretKey::from_slice(&candidate).is_ok() {
            return Ok(candidate);
        }
        counter = counter
            .checked_add(1)
            .ok_or(KeyHierarchyError::Secp256k1ScalarDerivationFailed)?;
    }
}
