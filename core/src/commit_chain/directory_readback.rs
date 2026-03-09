use base64::{engine::general_purpose::STANDARD, Engine as _};
use sha2::{Digest, Sha256};

use super::crypto::{
    decode_private_key, decrypt_metadata_blob, derive_branch_key, derive_master_key,
};
use super::{
    CommitChainError, DirectoryBlob, ReadDirectoryEntriesRequest, ReadDirectoryEntriesResponse,
};
use crate::mvp_write::UploadInstruction;

pub fn read_directory_entries(
    request: &ReadDirectoryEntriesRequest,
) -> Result<ReadDirectoryEntriesResponse, CommitChainError> {
    let private_key = decode_private_key(&request.private_key_hex)?;
    let master_key = derive_master_key(&private_key, &request.passphrase)?;
    let metadata_key = derive_branch_key(&master_key, b"garland-v1:metadata")?;
    let share = find_directory_share(request)?;
    let encrypted = STANDARD
        .decode(&share.body_b64)
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    let encrypted_hash = hex::encode(Sha256::digest(&encrypted));
    if encrypted_hash != request.root_inode.hash {
        return Err(CommitChainError::DirectoryShareMismatch);
    }
    let plaintext = decrypt_metadata_blob(&metadata_key, &encrypted)?;
    let directory: DirectoryBlob =
        serde_json::from_slice(&plaintext).map_err(|_| CommitChainError::DecryptionFailed)?;
    Ok(ReadDirectoryEntriesResponse {
        entries: directory.entries.keys().cloned().collect(),
        directory,
    })
}

fn find_directory_share(
    request: &ReadDirectoryEntriesRequest,
) -> Result<&UploadInstruction, CommitChainError> {
    request
        .root_inode
        .shares
        .iter()
        .find_map(|share_ref| {
            request
                .uploads
                .iter()
                .find(|upload| upload.share_id_hex == share_ref.id)
        })
        .ok_or(CommitChainError::MissingDirectoryShare)
}
