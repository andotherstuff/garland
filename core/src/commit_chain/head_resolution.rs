use std::collections::{HashMap, HashSet};

use super::crypto::{
    decode_private_key, decrypt_commit_payload, derive_branch_key, derive_master_key,
};
use super::{
    CommitChainError, DecryptedCommit, ResolveCommitChainHeadRequest,
    ResolveCommitChainHeadResponse,
};

pub fn resolve_commit_chain_head(
    request: &ResolveCommitChainHeadRequest,
) -> Result<ResolveCommitChainHeadResponse, CommitChainError> {
    let private_key = decode_private_key(&request.private_key_hex)?;
    let master_key = derive_master_key(&private_key, &request.passphrase)?;
    let commit_key = derive_branch_key(&master_key, b"garland-v1:commit")?;

    let decrypted = collect_decrypted_commits(request, &commit_key);
    let valid_ids = collect_valid_ids(&decrypted);
    if valid_ids.is_empty() {
        return Err(CommitChainError::NoValidHead);
    }

    let head = select_head(&decrypted, &valid_ids)?;
    enforce_trust(request, &head)?;

    let mut valid_event_ids = valid_ids;
    valid_event_ids.sort();
    Ok(ResolveCommitChainHeadResponse {
        head,
        valid_event_ids,
    })
}

fn collect_decrypted_commits(
    request: &ResolveCommitChainHeadRequest,
    commit_key: &[u8; 32],
) -> HashMap<String, DecryptedCommit> {
    request
        .events
        .iter()
        .filter(|event| event.kind == 1097 && event.tags.is_empty())
        .filter_map(|event| {
            let payload = decrypt_commit_payload(commit_key, &event.content).ok()?;
            Some((
                event.id_hex.clone(),
                DecryptedCommit {
                    event_id: event.id_hex.clone(),
                    pubkey_hex: event.pubkey_hex.clone(),
                    created_at: event.created_at,
                    payload,
                },
            ))
        })
        .collect()
}

fn collect_valid_ids(decrypted: &HashMap<String, DecryptedCommit>) -> Vec<String> {
    let mut memo = HashMap::new();
    let mut visiting = HashSet::new();
    decrypted
        .keys()
        .filter(|event_id| is_valid_commit(event_id, decrypted, &mut memo, &mut visiting))
        .cloned()
        .collect()
}

fn select_head(
    decrypted: &HashMap<String, DecryptedCommit>,
    valid_ids: &[String],
) -> Result<DecryptedCommit, CommitChainError> {
    let valid_set: HashSet<&str> = valid_ids.iter().map(String::as_str).collect();
    let referenced: HashSet<&str> = valid_ids
        .iter()
        .filter_map(|event_id| decrypted[event_id].payload.prev.as_deref())
        .filter(|event_id| valid_set.contains(event_id))
        .collect();
    let tips = valid_ids
        .iter()
        .filter(|event_id| !referenced.contains(event_id.as_str()))
        .map(|event_id| &decrypted[event_id])
        .collect::<Vec<_>>();
    let max_seq = tips
        .iter()
        .map(|commit| commit.payload.seq)
        .max()
        .ok_or(CommitChainError::NoValidHead)?;
    let best_tips = tips
        .into_iter()
        .filter(|commit| commit.payload.seq == max_seq)
        .collect::<Vec<_>>();

    if best_tips.len() != 1 {
        return Err(CommitChainError::ForkDetected);
    }
    Ok(best_tips[0].clone())
}

fn enforce_trust(
    request: &ResolveCommitChainHeadRequest,
    head: &DecryptedCommit,
) -> Result<(), CommitChainError> {
    if let Some(trusted_seq) = request.trusted_seq {
        if head.payload.seq < trusted_seq {
            return Err(CommitChainError::StaleHead);
        }
        if head.payload.seq == trusted_seq
            && request
                .trusted_event_id
                .as_ref()
                .is_some_and(|trusted_event_id| &head.event_id != trusted_event_id)
        {
            return Err(CommitChainError::ForkDetected);
        }
    }
    Ok(())
}

fn is_valid_commit(
    event_id: &str,
    decrypted: &HashMap<String, DecryptedCommit>,
    memo: &mut HashMap<String, bool>,
    visiting: &mut HashSet<String>,
) -> bool {
    if let Some(result) = memo.get(event_id) {
        return *result;
    }
    if !visiting.insert(event_id.to_owned()) {
        memo.insert(event_id.to_owned(), false);
        return false;
    }
    let Some(commit) = decrypted.get(event_id) else {
        visiting.remove(event_id);
        return false;
    };

    let result = match &commit.payload.prev {
        None => commit.payload.seq == 0,
        Some(parent_id) => decrypted.get(parent_id).is_some_and(|parent| {
            is_valid_commit(parent_id, decrypted, memo, visiting)
                && commit.payload.seq == parent.payload.seq + 1
        }),
    };

    visiting.remove(event_id);
    memo.insert(event_id.to_owned(), result);
    result
}
