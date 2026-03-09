pub mod commit_chain;
pub mod commit_crypto;
pub mod crypto;
pub mod erasure;
pub mod identity;
pub mod inode;
pub mod jni_api;
pub mod key_hierarchy;
pub mod mvp_write;
pub mod nostr_event;
pub mod packaging;

#[cfg(test)]
mod tests {
    use base64::Engine as _;
    use nostr::nips::nip06::FromMnemonic;
    use nostr::{Keys, SecretKey};
    use pretty_assertions::assert_eq;
    use serde_json::Value;

    use crate::commit_chain::{
        prepare_commit_chain_snapshot, read_directory_entries, resolve_commit_chain_head,
        PrepareCommitChainRequest, ReadDirectoryEntriesRequest, ResolveCommitChainHeadRequest,
    };
    use crate::commit_crypto::{decode_commit_content, decrypt_commit_payload};
    use crate::crypto::{
        decrypt_block, decrypt_metadata_block, encrypt_block, encrypt_metadata_block,
        prepare_erasure_coded_upload, prepare_metadata_replication_upload,
        prepare_replication_upload, BlossomServer, ErasureCodingConfig, REPLICATION_FACTOR,
    };
    use crate::erasure::rs_reconstruct;
    use crate::identity::derive_nostr_identity;
    use crate::key_hierarchy::{
        derive_blob_auth_private_key, derive_commit_key, derive_file_key, derive_master_key,
        derive_metadata_key,
    };
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
        let raw_identity =
            Keys::from_mnemonic_advanced(mnemonic, Some(""), Some(0), Some(0), Some(0))
                .expect("raw identity should derive");

        assert_eq!(identity.private_key_hex.len(), 64);
        assert!(identity.nsec.starts_with("nsec1"));
        assert_ne!(
            identity.private_key_hex,
            raw_identity.secret_key().to_secret_hex()
        );
        let second = derive_nostr_identity(mnemonic, "").expect("identity should re-derive");
        assert_eq!(identity, second);
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
        let empty =
            derive_nostr_identity(mnemonic, "").expect("empty-passphrase identity should derive");

        assert_eq!(identity.private_key_hex.len(), 64);
        assert!(identity.nsec.starts_with("nsec1"));
        assert_ne!(identity.private_key_hex, empty.private_key_hex);
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
    fn randomizes_padding_bytes() {
        let first = frame_content(b"garland").expect("first frame should build");
        let second = frame_content(b"garland").expect("second frame should build");

        assert_ne!(&first[4 + 7..], &second[4 + 7..]);
        assert_eq!(unframe_content(&first).unwrap(), b"garland");
        assert_eq!(unframe_content(&second).unwrap(), b"garland");
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
    fn encrypts_and_decrypts_metadata_block() {
        let metadata_key = [4_u8; 32];
        let nonce = [8_u8; 12];
        let content = br#"{"kind":"root","name":"/"}"#;

        let encrypted = encrypt_metadata_block(&metadata_key, &nonce, content)
            .expect("metadata should encrypt");
        assert_eq!(encrypted.len(), BLOCK_SIZE);

        let decrypted =
            decrypt_metadata_block(&metadata_key, &encrypted).expect("metadata should decrypt");
        assert_eq!(decrypted, content);
    }

    #[test]
    fn prepares_metadata_replication_uploads() {
        let servers = vec![
            BlossomServer::new("https://cdn.nostrcheck.me"),
            BlossomServer::new("https://blossom.nostr.build"),
            BlossomServer::new("https://blossom.yakihonne.com"),
        ];

        let upload = prepare_metadata_replication_upload(
            [4_u8; 32],
            [8_u8; 12],
            br#"{"kind":"root","name":"/"}"#,
            &servers,
        )
        .expect("metadata upload should prepare");

        assert_eq!(upload.shares.len(), REPLICATION_FACTOR);
        assert_eq!(upload.share_size, BLOCK_SIZE);
        assert!(upload
            .shares
            .windows(2)
            .all(|pair| pair[0].share_id_hex == pair[1].share_id_hex));
    }

    #[test]
    fn prepares_erasure_coded_upload_and_reconstructs() {
        let servers: Vec<BlossomServer> = (0..5)
            .map(|i| BlossomServer::new(&format!("https://blossom{i}.example")))
            .collect();
        let config = ErasureCodingConfig::new(3, 2);

        let file_key = [7_u8; 32];
        let nonce = [5_u8; 12];
        let content = b"erasure coded upload test content";

        let upload = prepare_erasure_coded_upload(file_key, 0, nonce, content, &servers, &config)
            .expect("erasure upload should prepare");

        assert_eq!(upload.k, 3);
        assert_eq!(upload.n, 5);
        assert_eq!(upload.shares.len(), 5);

        // Each share should have a unique hash (unlike MVP replication)
        let ids: Vec<&str> = upload
            .shares
            .iter()
            .map(|s| s.share_id_hex.as_str())
            .collect();
        for i in 0..ids.len() {
            for j in (i + 1)..ids.len() {
                assert_ne!(ids[i], ids[j], "shares {i} and {j} should differ");
            }
        }

        // Each share maps to the correct server
        for (i, share) in upload.shares.iter().enumerate() {
            assert_eq!(share.server_url, format!("https://blossom{i}.example"));
        }

        // Reconstruct from any 3 of 5 shares, then decrypt
        let available: Vec<(usize, &[u8])> = vec![
            (0, &upload.shares[0].body),
            (2, &upload.shares[2].body),
            (4, &upload.shares[4].body),
        ];
        let reconstructed = rs_reconstruct(&available, 3, 5).expect("reconstruct should succeed");
        // The reconstructed data is the padded encrypted block — first BLOCK_SIZE bytes
        let encrypted_block = &reconstructed[..BLOCK_SIZE];
        let decrypted =
            decrypt_block(&file_key, 0, encrypted_block).expect("decrypt should succeed");
        assert_eq!(decrypted, content);
    }

    #[test]
    fn erasure_coded_upload_rejects_wrong_server_count() {
        let servers: Vec<BlossomServer> = (0..3)
            .map(|i| BlossomServer::new(&format!("https://blossom{i}.example")))
            .collect();
        let config = ErasureCodingConfig::new(3, 2); // expects 5 servers

        let err =
            prepare_erasure_coded_upload([7_u8; 32], 0, [5_u8; 12], b"test", &servers, &config)
                .expect_err("should reject wrong server count");
        assert!(err.to_string().contains("5 servers, got 3"));
    }

    #[test]
    fn derives_protocol_keys_from_master_key() {
        let storage_private_key =
            hex::decode("7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a")
                .expect("private key should decode");
        let storage_private_key: [u8; 32] = storage_private_key
            .try_into()
            .expect("private key should be 32 bytes");
        let document_id = [0x24_u8; 32];
        let share_id = [0x11_u8; 32];

        let master_key = derive_master_key(&storage_private_key).expect("master key should derive");
        let commit_key = derive_commit_key(&master_key).expect("commit key should derive");
        let metadata_key = derive_metadata_key(&master_key).expect("metadata key should derive");
        let file_key = derive_file_key(&master_key, &document_id).expect("file key should derive");
        let blob_auth_key = derive_blob_auth_private_key(&master_key, &share_id)
            .expect("blob auth key should derive");

        assert_ne!(commit_key, metadata_key);
        assert_ne!(commit_key, file_key);
        assert_ne!(metadata_key, file_key);
        assert_ne!(blob_auth_key, master_key);
        SecretKey::from_slice(&blob_auth_key).expect("blob auth key should be a valid secret key");
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
        let private_key_bytes =
            hex::decode("7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a")
                .expect("private key should decode");
        let private_key_bytes: [u8; 32] = private_key_bytes
            .try_into()
            .expect("private key should be 32 bytes");
        let master_key = derive_master_key(&private_key_bytes).expect("master key should derive");
        let expected_secret_key = derive_blob_auth_private_key(&master_key, &[0x11_u8; 32])
            .expect("blob auth key should derive");
        let expected_pubkey = Keys::new(
            SecretKey::from_slice(&expected_secret_key).expect("blob auth key should be valid"),
        )
        .public_key()
        .to_hex();

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
        assert_eq!(first.pubkey_hex, expected_pubkey);
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
            display_name: "note.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: "bXZwIGZpbGU=".into(),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
            document_id: None,
            previous_event_id: None,
        };

        let plan = prepare_single_block_write(&request).expect("write plan should build");

        assert_eq!(plan.uploads.len(), 3);
        assert_eq!(plan.uploads[0].server_url, "https://cdn.nostrcheck.me");
        assert_eq!(plan.uploads[1].server_url, "https://blossom.nostr.build");
        assert_eq!(plan.uploads[2].server_url, "https://blossom.yakihonne.com");
        assert_eq!(plan.commit_event.kind, 1097);
        assert_eq!(plan.commit_event.tags.len(), 1);
        assert_eq!(plan.commit_event.tags[0][0], "d");
        assert_eq!(plan.commit_event.tags[0][1], plan.manifest.document_id);
        assert_eq!(plan.commit_event.id_hex.len(), 64);
        assert_eq!(plan.commit_event.sig_hex.len(), 128);
        assert_eq!(plan.manifest.document_id.len(), 64);
        assert!(!plan
            .commit_event
            .content
            .contains(&plan.manifest.document_id));
        assert!(!plan.commit_event.content.contains("note.txt"));
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
    }

    #[test]
    fn write_plan_document_ids_are_randomized() {
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            display_name: "note.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: "bXZwIGZpbGU=".into(),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
            document_id: None,
            previous_event_id: None,
        };

        let first = prepare_single_block_write(&request).expect("first write plan should build");
        let second = prepare_single_block_write(&request).expect("second write plan should build");

        assert_ne!(first.document_id, second.document_id);
    }

    #[test]
    fn recovers_single_block_write_content() {
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            display_name: "note.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: "aGVsbG8=".into(),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
            document_id: None,
            previous_event_id: None,
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
            display_name: "big.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: base64::engine::general_purpose::STANDARD.encode(payload),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
            document_id: None,
            previous_event_id: None,
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
            display_name: "big.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: base64::engine::general_purpose::STANDARD.encode(&payload),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
            document_id: None,
            previous_event_id: None,
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
        assert_eq!(snapshot.commit_event.tags.len(), 1);
        assert_eq!(snapshot.commit_event.tags[0][0], "nonce");
        // Nonce tag value should be valid base64 encoding of 12 bytes
        let nonce_bytes = base64::engine::general_purpose::STANDARD
            .decode(&snapshot.commit_event.tags[0][1])
            .expect("nonce tag should be valid base64");
        assert_eq!(nonce_bytes.len(), 12);
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
