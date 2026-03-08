use nostr::event::{Kind, Tag, UnsignedEvent as NostrUnsignedEvent};
use nostr::{Keys, SecretKey, Timestamp};
use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct UnsignedEvent {
    pub created_at: u64,
    pub kind: u64,
    pub tags: Vec<Vec<String>>,
    pub content: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
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
