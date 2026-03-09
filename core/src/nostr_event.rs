use core::str::FromStr;

use nostr::event::{Kind, Tag, UnsignedEvent as NostrUnsignedEvent};
use nostr::secp256k1::schnorr::Signature;
use nostr::{Event, EventId, Keys, PublicKey, SecretKey, Timestamp};
use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::key_hierarchy::{derive_blob_auth_private_key, derive_master_key};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UnsignedEvent {
    pub created_at: u64,
    pub kind: u64,
    pub tags: Vec<Vec<String>>,
    pub content: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SignedEvent {
    pub id_hex: String,
    pub pubkey_hex: String,
    pub created_at: u64,
    pub kind: u64,
    pub tags: Vec<Vec<String>>,
    pub content: String,
    pub sig_hex: String,
}

#[derive(Debug, Error)]
pub enum NostrEventError {
    #[error("private key hex is invalid")]
    InvalidPrivateKey,
    #[error("event kind is invalid")]
    InvalidKind,
    #[error("event tags are invalid")]
    InvalidTags,
    #[error("share ID hex is invalid")]
    InvalidShareId,
    #[error("event signing failed")]
    SigningFailed,
}

pub fn sign_custom_event(
    private_key_hex: &str,
    event: &UnsignedEvent,
) -> Result<SignedEvent, NostrEventError> {
    let secret_key =
        SecretKey::from_hex(private_key_hex).map_err(|_| NostrEventError::InvalidPrivateKey)?;
    let keys = Keys::new(secret_key);
    let kind = u16::try_from(event.kind).map_err(|_| NostrEventError::InvalidKind)?;
    let tags = event
        .tags
        .iter()
        .map(|tag| Tag::parse(tag.clone()).map_err(|_| NostrEventError::InvalidTags))
        .collect::<Result<Vec<_>, _>>()?;
    let signed = NostrUnsignedEvent::new(
        keys.public_key(),
        Timestamp::from_secs(event.created_at),
        Kind::from(kind),
        tags,
        event.content.clone(),
    )
    .sign_with_keys(&keys)
    .map_err(|_| NostrEventError::SigningFailed)?;

    Ok(SignedEvent {
        id_hex: signed.id.to_hex(),
        pubkey_hex: signed.pubkey.to_hex(),
        created_at: signed.created_at.as_secs(),
        kind: u16::from(signed.kind) as u64,
        tags: signed
            .tags
            .into_iter()
            .map(|tag| tag.as_slice().to_vec())
            .collect(),
        content: signed.content,
        sig_hex: signed.sig.to_string(),
    })
}

pub fn verify_signed_event(event: &SignedEvent) -> bool {
    signed_event_to_nostr_event(event)
        .map(|nostr_event| nostr_event.verify().is_ok())
        .unwrap_or(false)
}

pub fn sign_blossom_upload_auth_event(
    private_key_hex: &str,
    share_id_hex: &str,
    server_url: &str,
    size_bytes: u64,
    created_at: u64,
    expiration: u64,
) -> Result<SignedEvent, NostrEventError> {
    let derived_private_key_hex = derive_blob_auth_private_key_hex(private_key_hex, share_id_hex)?;
    let event = UnsignedEvent {
        created_at,
        kind: 24_242,
        tags: vec![
            vec!["t".into(), "upload".into()],
            vec!["x".into(), share_id_hex.to_lowercase()],
            vec!["server".into(), server_url.to_string()],
            vec!["size".into(), size_bytes.to_string()],
            vec!["expiration".into(), expiration.to_string()],
        ],
        content: "garland upload authorization".into(),
    };
    sign_custom_event(&derived_private_key_hex, &event)
}

fn derive_blob_auth_private_key_hex(
    private_key_hex: &str,
    share_id_hex: &str,
) -> Result<String, NostrEventError> {
    let private_key_bytes =
        hex::decode(private_key_hex).map_err(|_| NostrEventError::InvalidPrivateKey)?;
    let private_key_bytes: [u8; 32] = private_key_bytes
        .try_into()
        .map_err(|_| NostrEventError::InvalidPrivateKey)?;
    let share_id = hex::decode(share_id_hex).map_err(|_| NostrEventError::InvalidShareId)?;
    let share_id: [u8; 32] = share_id
        .try_into()
        .map_err(|_| NostrEventError::InvalidShareId)?;
    let master_key =
        derive_master_key(&private_key_bytes).map_err(|_| NostrEventError::SigningFailed)?;
    let blob_auth_private_key = derive_blob_auth_private_key(&master_key, &share_id)
        .map_err(|_| NostrEventError::SigningFailed)?;

    SecretKey::from_slice(&blob_auth_private_key).map_err(|_| NostrEventError::SigningFailed)?;
    Ok(hex::encode(blob_auth_private_key))
}

fn signed_event_to_nostr_event(event: &SignedEvent) -> Result<Event, NostrEventError> {
    let id = EventId::from_hex(&event.id_hex).map_err(|_| NostrEventError::InvalidTags)?;
    let pubkey =
        PublicKey::from_hex(&event.pubkey_hex).map_err(|_| NostrEventError::InvalidTags)?;
    let kind = u16::try_from(event.kind).map_err(|_| NostrEventError::InvalidKind)?;
    let tags = event
        .tags
        .iter()
        .map(|tag| Tag::parse(tag.clone()).map_err(|_| NostrEventError::InvalidTags))
        .collect::<Result<Vec<_>, _>>()?;
    let sig = Signature::from_str(&event.sig_hex).map_err(|_| NostrEventError::SigningFailed)?;

    Ok(Event::new(
        id,
        pubkey,
        Timestamp::from_secs(event.created_at),
        Kind::from(kind),
        tags,
        event.content.clone(),
        sig,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_PRIVATE_KEY_HEX: &str =
        "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a";

    #[test]
    fn verifies_signed_events_and_rejects_tampering() {
        let event = sign_custom_event(
            TEST_PRIVATE_KEY_HEX,
            &UnsignedEvent {
                created_at: 1_701_907_200,
                kind: 10_97,
                tags: vec![vec!["d".into(), "doc-id".into()]],
                content: "encrypted-content".into(),
            },
        )
        .expect("event should sign");

        assert!(verify_signed_event(&event));

        let mut tampered_content = event.clone();
        tampered_content.content = "tampered-content".into();
        assert!(!verify_signed_event(&tampered_content));

        let mut tampered_id = event.clone();
        tampered_id.id_hex = "0".repeat(64);
        assert!(!verify_signed_event(&tampered_id));
    }
}
