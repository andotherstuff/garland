use hkdf::Hkdf;
use thiserror::Error;

use sha2::Sha256;

#[derive(Debug, Error)]
pub enum KeyHierarchyError {
    #[error("hkdf expansion failed")]
    HkdfExpansionFailed,
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
