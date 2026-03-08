pub mod commit_chain;
pub mod commit_crypto;
pub mod crypto;
pub mod identity;
pub mod jni_api;
pub mod mvp_write;
pub mod nostr_event;
pub mod packaging;

#[cfg(test)]
mod tests {
    use base64::Engine as _;
    use pretty_assertions::assert_eq;
    use serde_json::Value;

    use crate::commit_chain::{
        prepare_commit_chain_snapshot, read_directory_entries, resolve_commit_chain_head,
        PrepareCommitChainRequest, ReadDirectoryEntriesRequest, ResolveCommitChainHeadRequest,
    };
    use crate::commit_crypto::{decode_commit_content, decrypt_commit_payload};
    use crate::crypto::{
        decrypt_block, encrypt_block, prepare_replication_upload, BlossomServer, REPLICATION_FACTOR,
    };
    use crate::identity::derive_nostr_identity;
    use crate::mvp_write::{
        prepare_single_block_write, recover_single_block_read, PrepareWriteRequest,
        RecoverReadRequest,
    };
    use crate::nostr_event::{sign_blossom_upload_auth_event, sign_custom_event, UnsignedEvent};
    use crate::packaging::{
        frame_content, unframe_content, BLOCK_SIZE, CONTENT_CAPACITY, FRAME_SIZE,
    };

    #[test]
    fn derives_known_nip06_vector() {
        let mnemonic =
            "leader monkey parrot ring guide accident before fence cannon height naive bean";
        let identity = derive_nostr_identity(mnemonic, "").expect("identity should derive");

        assert_eq!(
            identity.private_key_hex,
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
        );
        assert_eq!(
            identity.nsec,
            "nsec10allq0gjx7fddtzef0ax00mdps9t2kmtrldkyjfs8l5xruwvh2dq0lhhkp"
        );
    }

    #[test]
    fn rejects_invalid_mnemonic_checksum() {
        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon";
        let error = derive_nostr_identity(mnemonic, "").expect_err("checksum should fail");
        assert!(error.to_string().contains("mnemonic"));
    }

    #[test]
    fn derives_identity_with_passphrase() {
        let mnemonic =
            "leader monkey parrot ring guide accident before fence cannon height naive bean";
        let identity = derive_nostr_identity(mnemonic, "garland-passphrase")
            .expect("identity should derive with passphrase");

        assert_eq!(identity.private_key_hex.len(), 64);
        assert!(identity.nsec.starts_with("nsec1"));
        assert_ne!(
            identity.private_key_hex,
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
        );
    }

    #[test]
    fn frames_and_unframes_short_content() {
        let content = b"garland";
        let frame = frame_content(content).expect("frame should build");

        assert_eq!(frame.len(), FRAME_SIZE);
        assert_eq!(
            u32::from_be_bytes(frame[..4].try_into().unwrap()),
            content.len() as u32
        );

        let recovered = unframe_content(&frame).expect("frame should decode");
        assert_eq!(recovered, content);
    }

    #[test]
    fn frames_empty_content() {
        let frame = frame_content(&[]).expect("empty content should frame");
        assert_eq!(frame.len(), FRAME_SIZE);
        let recovered = unframe_content(&frame).expect("frame should decode");
        assert_eq!(recovered, b"");
    }

    #[test]
    fn rejects_content_larger_than_effective_block_capacity() {
        let content = vec![7_u8; FRAME_SIZE - 3];
        let error = frame_content(&content).expect_err("oversized content should fail");
        assert!(error.to_string().contains("too large"));
    }

    #[test]
    fn exposes_spec_sizes() {
        assert_eq!(BLOCK_SIZE, 262_144);
        assert_eq!(FRAME_SIZE, 262_100);
    }

    #[test]
    fn encrypts_and_decrypts_a_single_block() {
        let file_key = [9_u8; 32];
        let nonce = [3_u8; 12];
        let content = b"garland encrypted block";

        let encrypted = encrypt_block(&file_key, 0, &nonce, content).expect("block should encrypt");
        assert_eq!(encrypted.len(), BLOCK_SIZE);

        let decrypted = decrypt_block(&file_key, 0, &encrypted).expect("block should decrypt");
        assert_eq!(decrypted, content);
    }

    #[test]
    fn prepares_three_replicated_uploads() {
        let servers = vec![
            BlossomServer::new("https://cdn.nostrcheck.me"),
            BlossomServer::new("https://blossom.nostr.build"),
            BlossomServer::new("https://blossom.yakihonne.com"),
        ];

        let upload = prepare_replication_upload([7_u8; 32], 0, [5_u8; 12], b"mvp upload", &servers)
            .expect("upload should prepare");

        assert_eq!(upload.shares.len(), REPLICATION_FACTOR);
        assert_eq!(upload.share_size, BLOCK_SIZE);
        assert!(upload
            .shares
            .windows(2)
            .all(|pair| pair[0].share_id_hex == pair[1].share_id_hex));
        assert_eq!(upload.shares[0].server_url, "https://cdn.nostrcheck.me");
        assert_eq!(upload.shares[1].server_url, "https://blossom.nostr.build");
        assert_eq!(upload.shares[2].server_url, "https://blossom.yakihonne.com");
    }

    #[test]
    fn signs_custom_nostr_event() {
        let event = UnsignedEvent {
            created_at: 1_701_907_200,
            kind: 24_242,
            tags: vec![vec!["t".into(), "upload".into()]],
            content: "garland upload authorization".into(),
        };

        let signed = sign_custom_event(
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
            &event,
        )
        .expect("event should sign");

        assert_eq!(signed.id_hex.len(), 64);
        assert_eq!(signed.pubkey_hex.len(), 64);
        assert_eq!(signed.sig_hex.len(), 128);
    }

    #[test]
    fn derives_distinct_blob_auth_keys_per_share() {
        let first = sign_blossom_upload_auth_event(
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
            &"11".repeat(32),
            1_701_907_200,
            1_701_907_500,
        )
        .expect("first auth event should sign");
        let second = sign_blossom_upload_auth_event(
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
            &"22".repeat(32),
            1_701_907_200,
            1_701_907_500,
        )
        .expect("second auth event should sign");

        assert_ne!(first.pubkey_hex, second.pubkey_hex);
        assert_eq!(first.tags[0], vec!["t".to_string(), "upload".to_string()]);
        assert_eq!(first.tags[1][0], "x");
        assert_eq!(first.tags[1][1], "11".repeat(32));
    }

    #[test]
    fn rejects_custom_event_kind_outside_u16_range() {
        let event = UnsignedEvent {
            created_at: 1_701_907_200,
            kind: u16::MAX as u64 + 1,
            tags: vec![vec!["t".into(), "upload".into()]],
            content: "garland upload authorization".into(),
        };

        let error = sign_custom_event(
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
            &event,
        )
        .expect_err("event kind outside u16 range should fail");

        assert_eq!(error.to_string(), "event kind is invalid");
    }

    #[test]
    fn rejects_custom_event_with_empty_tag() {
        let event = UnsignedEvent {
            created_at: 1_701_907_200,
            kind: 24_242,
            tags: vec![Vec::new()],
            content: "garland upload authorization".into(),
        };

        let error = sign_custom_event(
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
            &event,
        )
        .expect_err("empty tag should fail");

        assert_eq!(error.to_string(), "event tags are invalid");
    }

    #[test]
    fn prepares_single_block_write_contract() {
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            created_at: 1_701_907_200,
            content_b64: "bXZwIGZpbGU=".into(),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
        };

        let plan = prepare_single_block_write(&request).expect("write plan should build");

        assert_eq!(plan.uploads.len(), 3);
        assert_eq!(plan.uploads[0].server_url, "https://cdn.nostrcheck.me");
        assert_eq!(plan.uploads[1].server_url, "https://blossom.nostr.build");
        assert_eq!(plan.uploads[2].server_url, "https://blossom.yakihonne.com");
        assert_eq!(plan.commit_event.kind, 1097);
        assert_eq!(plan.commit_event.tags.len(), 0);
        assert_eq!(plan.commit_event.id_hex.len(), 64);
        assert_eq!(plan.commit_event.sig_hex.len(), 128);
        assert_eq!(plan.manifest.document_id.len(), 64);
        let manifest_json =
            serde_json::to_string(&plan.manifest).expect("manifest should serialize");
        assert!(!manifest_json.contains("display_name"));
        assert!(!manifest_json.contains("mime_type"));
        assert!(!plan
            .commit_event
            .content
            .contains(&plan.manifest.document_id));
        assert!(!plan
            .commit_event
            .content
            .contains(&plan.manifest.sha256_hex));
        assert!(!plan
            .commit_event
            .content
            .contains(&plan.manifest.blocks[0].share_id_hex));
        assert!(!plan.commit_event.content.contains("cdn.nostrcheck.me"));
        assert!(plan.commit_event.content.contains("\"version\":1"));
        let encrypted_content = decode_commit_content(&plan.commit_event.content)
            .expect("commit content should decode");
        let decrypted_content =
            decrypt_commit_payload(&request.private_key_hex, &encrypted_content)
                .expect("commit payload should decrypt");
        let payload: Value =
            serde_json::from_slice(&decrypted_content).expect("commit payload should parse");
        assert_eq!(
            payload.get("document_id").and_then(Value::as_str),
            Some(plan.manifest.document_id.as_str())
        );
        assert_eq!(
            payload.get("size_bytes").and_then(Value::as_u64),
            Some(plan.manifest.size_bytes as u64)
        );
        assert!(payload.get("created_at").is_none());
    }

    #[test]
    fn write_plan_document_ids_are_randomized() {
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            created_at: 1_701_907_200,
            content_b64: "bXZwIGZpbGU=".into(),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
        };

        let first = prepare_single_block_write(&request).expect("first write plan should build");
        let second = prepare_single_block_write(&request).expect("second write plan should build");

        assert_ne!(first.document_id, second.document_id);
    }

    #[test]
    fn tampered_commit_payload_fails_authentication() {
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            created_at: 1_701_907_200,
            content_b64: "bXZwIGZpbGU=".into(),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
        };

        let plan = prepare_single_block_write(&request).expect("write plan should build");
        let mut encrypted_content = decode_commit_content(&plan.commit_event.content)
            .expect("commit content should decode");
        encrypted_content[12] ^= 0x01;

        let error = decrypt_commit_payload(&request.private_key_hex, &encrypted_content)
            .expect_err("tampered payload should fail auth");
        assert_eq!(error.to_string(), "commit payload authentication failed");
    }

    #[test]
    fn commit_content_rejects_wrong_key_and_invalid_envelope() {
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            created_at: 1_701_907_200,
            content_b64: "bXZwIGZpbGU=".into(),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
        };

        let plan = prepare_single_block_write(&request).expect("write plan should build");
        let encrypted_content = decode_commit_content(&plan.commit_event.content)
            .expect("commit content envelope should decode");

        let wrong_key_error = decrypt_commit_payload(
            "8f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
            &encrypted_content,
        )
        .expect_err("wrong key should fail auth");
        assert_eq!(
            wrong_key_error.to_string(),
            "commit payload authentication failed"
        );

        let envelope_error = decode_commit_content(
            "{\"version\":2,\"algorithm\":\"wrong\",\"ciphertext_b64\":\"AA==\"}",
        )
        .expect_err("invalid envelope should fail");
        assert_eq!(
            envelope_error.to_string(),
            "commit content envelope is invalid"
        );
    }

    #[test]
    fn recovers_single_block_write_content() {
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            created_at: 1_701_907_200,
            content_b64: "aGVsbG8=".into(),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
        };

        let plan = prepare_single_block_write(&request).expect("write plan should build");
        let recovered = recover_single_block_read(&RecoverReadRequest {
            private_key_hex: request.private_key_hex,
            document_id: plan.manifest.document_id,
            block_index: 0,
            encrypted_block_b64: plan.uploads[0].body_b64.clone(),
        })
        .expect("content should recover");

        assert_eq!(recovered, b"hello");
    }

    #[test]
    fn prepares_multi_block_write_contract() {
        let payload = vec![b'g'; CONTENT_CAPACITY + 17];
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            created_at: 1_701_907_200,
            content_b64: base64::engine::general_purpose::STANDARD.encode(payload),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
        };

        let plan =
            prepare_single_block_write(&request).expect("multi-block write plan should build");

        assert_eq!(plan.manifest.blocks.len(), 2);
        assert_eq!(plan.uploads.len(), 6);
        assert_eq!(plan.manifest.blocks[0].index, 0);
        assert_eq!(plan.manifest.blocks[1].index, 1);
    }

    #[test]
    fn recovers_multi_block_write_content() {
        let payload = vec![b'z'; CONTENT_CAPACITY + 17];
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            created_at: 1_701_907_200,
            content_b64: base64::engine::general_purpose::STANDARD.encode(&payload),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
        };

        let plan =
            prepare_single_block_write(&request).expect("multi-block write plan should build");
        let recovered = plan
            .manifest
            .blocks
            .iter()
            .map(|block| {
                let encrypted = plan
                    .uploads
                    .iter()
                    .find(|upload| upload.share_id_hex == block.share_id_hex)
                    .expect("upload should exist");
                recover_single_block_read(&RecoverReadRequest {
                    private_key_hex: request.private_key_hex.clone(),
                    document_id: plan.manifest.document_id.clone(),
                    block_index: block.index,
                    encrypted_block_b64: encrypted.body_b64.clone(),
                })
                .expect("block should recover")
            })
            .flatten()
            .collect::<Vec<_>>();

        assert_eq!(recovered, payload);
    }

    #[test]
    fn prepares_commit_chain_snapshot_contract() {
        let request = PrepareCommitChainRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            passphrase: "bucket-passphrase".into(),
            created_at: 1_701_907_200,
            prev_event_id: Some("ab".repeat(32)),
            prev_seq: Some(7),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
            entry_names: vec!["note.txt".into(), "todo.txt".into()],
            message: Some("snapshot".into()),
        };

        let snapshot = prepare_commit_chain_snapshot(&request).expect("snapshot should build");

        assert_eq!(snapshot.payload.prev, request.prev_event_id);
        assert_eq!(snapshot.payload.seq, 8);
        assert_eq!(snapshot.payload.message.as_deref(), Some("snapshot"));
        assert_eq!(snapshot.root_directory.entries.len(), 2);
        assert_eq!(snapshot.uploads.len(), 3);
        assert_eq!(snapshot.commit_event.kind, 1097);
        assert_eq!(snapshot.commit_event.tags.len(), 0);
        assert_eq!(snapshot.root_inode.format, "single");
        assert_eq!(snapshot.root_inode.erasure.k, 1);
        assert_eq!(snapshot.root_inode.erasure.n, 3);
    }

    #[test]
    fn resolves_latest_valid_commit_chain_head() {
        let private_key_hex =
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".to_string();
        let passphrase = "bucket-passphrase".to_string();
        let servers = vec![
            "https://cdn.nostrcheck.me".to_string(),
            "https://blossom.nostr.build".to_string(),
            "https://blossom.yakihonne.com".to_string(),
        ];
        let first = prepare_commit_chain_snapshot(&PrepareCommitChainRequest {
            private_key_hex: private_key_hex.clone(),
            passphrase: passphrase.clone(),
            created_at: 1_701_907_200,
            prev_event_id: None,
            prev_seq: None,
            servers: servers.clone(),
            entry_names: vec!["note.txt".into()],
            message: Some("genesis".into()),
        })
        .expect("genesis should build");
        let second = prepare_commit_chain_snapshot(&PrepareCommitChainRequest {
            private_key_hex: private_key_hex.clone(),
            passphrase: passphrase.clone(),
            created_at: 1_701_907_260,
            prev_event_id: Some(first.commit_event.id_hex.clone()),
            prev_seq: Some(first.payload.seq),
            servers,
            entry_names: vec!["note.txt".into(), "todo.txt".into()],
            message: Some("second".into()),
        })
        .expect("second snapshot should build");

        let resolved = resolve_commit_chain_head(&ResolveCommitChainHeadRequest {
            private_key_hex,
            passphrase,
            events: vec![first.commit_event, second.commit_event.clone()],
            trusted_event_id: None,
            trusted_seq: None,
        })
        .expect("head should resolve");

        assert_eq!(resolved.head.event_id, second.commit_event.id_hex);
        assert_eq!(resolved.head.payload.seq, 1);
        assert_eq!(resolved.valid_event_ids.len(), 2);
    }

    #[test]
    fn rejects_forked_or_stale_commit_heads() {
        let private_key_hex =
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".to_string();
        let passphrase = "bucket-passphrase".to_string();
        let servers = vec![
            "https://cdn.nostrcheck.me".to_string(),
            "https://blossom.nostr.build".to_string(),
            "https://blossom.yakihonne.com".to_string(),
        ];
        let genesis = prepare_commit_chain_snapshot(&PrepareCommitChainRequest {
            private_key_hex: private_key_hex.clone(),
            passphrase: passphrase.clone(),
            created_at: 1_701_907_200,
            prev_event_id: None,
            prev_seq: None,
            servers: servers.clone(),
            entry_names: vec!["note.txt".into()],
            message: None,
        })
        .expect("genesis should build");
        let branch_a = prepare_commit_chain_snapshot(&PrepareCommitChainRequest {
            private_key_hex: private_key_hex.clone(),
            passphrase: passphrase.clone(),
            created_at: 1_701_907_260,
            prev_event_id: Some(genesis.commit_event.id_hex.clone()),
            prev_seq: Some(genesis.payload.seq),
            servers: servers.clone(),
            entry_names: vec!["note-a.txt".into()],
            message: None,
        })
        .expect("branch a should build");
        let branch_b = prepare_commit_chain_snapshot(&PrepareCommitChainRequest {
            private_key_hex: private_key_hex.clone(),
            passphrase: passphrase.clone(),
            created_at: 1_701_907_320,
            prev_event_id: Some(genesis.commit_event.id_hex.clone()),
            prev_seq: Some(genesis.payload.seq),
            servers: servers.clone(),
            entry_names: vec!["note-b.txt".into()],
            message: None,
        })
        .expect("branch b should build");

        let fork_error = resolve_commit_chain_head(&ResolveCommitChainHeadRequest {
            private_key_hex: private_key_hex.clone(),
            passphrase: passphrase.clone(),
            events: vec![
                genesis.commit_event.clone(),
                branch_a.commit_event.clone(),
                branch_b.commit_event,
            ],
            trusted_event_id: None,
            trusted_seq: None,
        })
        .expect_err("forked tips should fail");
        assert_eq!(fork_error.to_string(), "commit chain is forked");

        let stale_error = resolve_commit_chain_head(&ResolveCommitChainHeadRequest {
            private_key_hex,
            passphrase,
            events: vec![genesis.commit_event, branch_a.commit_event.clone()],
            trusted_event_id: Some(branch_a.commit_event.id_hex.clone()),
            trusted_seq: Some(branch_a.payload.seq + 1),
        })
        .expect_err("lower sequence than trusted head should fail");
        assert_eq!(stale_error.to_string(), "candidate head is stale");
    }

    #[test]
    fn reads_directory_entries_from_snapshot_uploads() {
        let private_key_hex =
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".to_string();
        let passphrase = "bucket-passphrase".to_string();
        let snapshot = prepare_commit_chain_snapshot(&PrepareCommitChainRequest {
            private_key_hex: private_key_hex.clone(),
            passphrase: passphrase.clone(),
            created_at: 1_701_907_200,
            prev_event_id: None,
            prev_seq: None,
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
            entry_names: vec!["note.txt".into(), "todo.txt".into()],
            message: None,
        })
        .expect("snapshot should build");

        let directory = read_directory_entries(&ReadDirectoryEntriesRequest {
            private_key_hex,
            passphrase,
            root_inode: snapshot.root_inode.clone(),
            uploads: snapshot.uploads.clone(),
        })
        .expect("directory should decode");

        assert_eq!(
            directory.entries,
            vec!["note.txt".to_string(), "todo.txt".to_string()]
        );
        assert_eq!(directory.directory.entries.len(), 2);
    }

    #[test]
    fn rejects_cyclic_commit_graphs() {
        let private_key_hex =
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".to_string();
        let passphrase = "bucket-passphrase".to_string();
        let servers = vec![
            "https://cdn.nostrcheck.me".to_string(),
            "https://blossom.nostr.build".to_string(),
            "https://blossom.yakihonne.com".to_string(),
        ];
        let mut first = prepare_commit_chain_snapshot(&PrepareCommitChainRequest {
            private_key_hex: private_key_hex.clone(),
            passphrase: passphrase.clone(),
            created_at: 1_701_907_200,
            prev_event_id: Some("b".repeat(64)),
            prev_seq: Some(0),
            servers: servers.clone(),
            entry_names: vec!["note.txt".into()],
            message: None,
        })
        .expect("first cyclic snapshot should build");
        let mut second = prepare_commit_chain_snapshot(&PrepareCommitChainRequest {
            private_key_hex: private_key_hex.clone(),
            passphrase: passphrase.clone(),
            created_at: 1_701_907_260,
            prev_event_id: Some("a".repeat(64)),
            prev_seq: Some(0),
            servers,
            entry_names: vec!["todo.txt".into()],
            message: None,
        })
        .expect("second cyclic snapshot should build");
        first.commit_event.id_hex = "a".repeat(64);
        second.commit_event.id_hex = "b".repeat(64);

        let error = resolve_commit_chain_head(&ResolveCommitChainHeadRequest {
            private_key_hex,
            passphrase,
            events: vec![first.commit_event, second.commit_event],
            trusted_event_id: None,
            trusted_seq: None,
        })
        .expect_err("cyclic commit graph should be rejected");

        assert_eq!(error.to_string(), "no valid commit head found");
    }
}
