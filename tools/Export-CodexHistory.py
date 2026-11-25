#!/usr/bin/env python3
"""Export scoped Codex history without changing the live Codex databases.

Requires Python 3.8+. Compressed rollouts use zstd on PATH, --zstd, or
the optional zstandard Python package. Generated JSONL is not an import API.
"""
import argparse
import base64
import datetime as dt
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import sqlite3
import subprocess
import sys
import urllib.parse

TZ = dt.timezone(dt.timedelta(hours=8), "Asia/Shanghai")
DEFAULT_IDS = {
    "019f949d-9276-78a0-9c7c-d2555209d57c": "StockShotManager initial implementation; requirement document v20",
    "019f94dc-cda2-7210-a86a-bfdd669cc6ae": "StockShotManager indicator image recognition reference",
    "019f9d0c-a3c9-7c60-9992-4f989981cb68": "StockShotManager development and inherited initial conversation",
    "019f9dfd-356b-73c2-bd24-44867e11f773": "StockShotManager continuation and inherited initial conversation",
    "019fa3b0-4ea3-7d13-9ea1-79f53b846c5e": "StockShotManager July continuation",
    "019fa933-cfa8-7863-aaa5-02d0385978b0": "Project progress report including StockShotManager background",
    "01a0e0d9-d0e9-7f92-b236-4271acc325fe": "StockShotManager highest price, capture date and table requirements",
    "01a0e29e-ae43-7d71-8a33-520bb8be1ec1": "StockShotManager September continuation and APK delivery",
    "01a20de5-5e5f-7e30-8f07-f34b2a3e1d56": "Current StockShotManager transfer conversation",
}
RELATION_KEYS = {"parent_thread_id", "parentThreadId", "forked_from_id", "attached_thread_parent_id"}
HUMAN_TYPES = {"userMessage", "agentMessage"}
ATTACHMENT_RE = re.compile(r"[A-Za-z]:[\\/][^\r\n\"'<>]*?\.codex[\\/]attachments[\\/][^\r\n\"'<>|]+", re.I)


def jsonable(value):
    if isinstance(value, bytes):
        return {"__bytes_base64__": base64.b64encode(value).decode("ascii")}
    if isinstance(value, dict):
        return {k: jsonable(v) for k, v in value.items()}
    if isinstance(value, (tuple, list)):
        return [jsonable(v) for v in value]
    return value


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(jsonable(value), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(jsonable(row), ensure_ascii=False, separators=(",", ":")) + "\n")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from strings(item)
    elif isinstance(value, (list, tuple)):
        for item in value:
            yield from strings(item)


def parent_ids(value):
    found = set()
    if isinstance(value, str):
        try:
            return parent_ids(json.loads(value))
        except (ValueError, TypeError):
            return found
    if isinstance(value, dict):
        for key, child in value.items():
            if key in RELATION_KEYS and isinstance(child, str):
                found.add(child)
            found.update(parent_ids(child))
    elif isinstance(value, (list, tuple)):
        for child in value:
            found.update(parent_ids(child))
    return found


def clean_path(value):
    value = value.strip().strip("`\"'").replace("\\\\?\\", "")
    if value.startswith("file://"):
        parsed = urllib.parse.urlparse(value)
        value = urllib.parse.unquote(parsed.path)
        if re.match(r"/[A-Za-z]:", value):
            value = value[1:]
    return pathlib.Path(value)


def local_time(value):
    if value is None:
        return "unknown"
    if value > 100000000000:
        value /= 1000
    return dt.datetime.fromtimestamp(value, TZ).isoformat()


class Exporter:
    def __init__(self, source, output, zstd=None, extra_ids=()):
        self.source = source.resolve()
        self.output = output.resolve()
        if self.output == self.source or self.source in self.output.parents:
            raise ValueError("Output must be outside the live CODEX_HOME directory")
        self.output.mkdir(parents=True, exist_ok=True)
        self.zstd = zstd or shutil.which("zstd")
        self.reasons = dict(DEFAULT_IDS)
        self.reasons.update({sid: "Explicitly requested extra thread" for sid in extra_ids})
        self.assets = {}
        self.missing = []
        self.raw_records = []
        self.resolutions = []
        self.start = dt.datetime.now(TZ).isoformat()

    def open_snapshot(self):
        state = self.source / "state_5.sqlite"
        history = self.source / "thread_history_1.sqlite"
        connection = sqlite3.connect(state.as_uri() + "?mode=ro", uri=True)
        connection.row_factory = sqlite3.Row
        connection.execute("ATTACH DATABASE ? AS history", (history.as_uri() + "?mode=ro",))
        connection.execute("PRAGMA query_only = ON")
        connection.execute("BEGIN")
        connection.execute("SELECT COUNT(*) FROM threads").fetchone()
        connection.execute("SELECT COUNT(*) FROM history.thread_items").fetchone()
        return connection

    def select_threads(self, connection):
        all_rows = {row["id"]: dict(row) for row in connection.execute("SELECT * FROM threads")}
        edges = [dict(row) for row in connection.execute("SELECT * FROM thread_spawn_edges")]
        for sid, row in all_rows.items():
            cwd = row.get("cwd", "").replace("\\", "/").rstrip("/").lower()
            title = row.get("title", "")
            if "stockshot" in title.lower() or "股票截图" in title:
                self.reasons.setdefault(sid, "Thread title explicitly names StockShotManager")
            elif cwd.endswith("/stockshotmanager"):
                self.reasons.setdefault(sid, "Thread working directory is StockShotManager")
        changed = True
        while changed:
            changed = False
            for edge in edges:
                parent, child = edge["parent_thread_id"], edge["child_thread_id"]
                if parent in self.reasons and child not in self.reasons:
                    self.reasons[child] = "Subagent of selected thread " + parent
                    changed = True
            for sid, row in all_rows.items():
                relations = set()
                for key in ("source", "thread_source", "attached_thread_parent_id"):
                    if key == "attached_thread_parent_id" and row.get(key):
                        relations.add(row[key])
                    else:
                        relations.update(parent_ids(row.get(key)))
                matched = relations.intersection(self.reasons)
                if sid not in self.reasons and matched:
                    self.reasons[sid] = "Source metadata links selected parent " + sorted(matched)[0]
                    changed = True
        selected = {sid: all_rows[sid] for sid in self.reasons if sid in all_rows}
        for sid in self.reasons:
            if sid not in selected:
                self.missing.append({"kind": "thread_metadata", "thread_id": sid, "reason": "No row in state_5.sqlite"})
        return selected, [edge for edge in edges if edge["parent_thread_id"] in selected or edge["child_thread_id"] in selected]

    def add_asset(self, source_text, reference):
        if not source_text or not isinstance(source_text, str):
            return None
        if source_text.startswith("data:image/"):
            header, _, encoded = source_text.partition(",")
            if ";base64" not in header:
                return None
            data = base64.b64decode(encoded)
            digest = hashlib.sha256(data).hexdigest()
            key = "embedded-image:" + digest
            extension = {"image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp", "image/gif": ".gif"}.get(header[5:].split(";")[0], ".bin")
            target = self.output / "attachments" / "embedded" / (digest + extension)
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists():
                target.write_bytes(data)
            entry = self.assets.setdefault(key, {"source": key, "relative_path": target.relative_to(self.output).as_posix(), "sha256": digest, "bytes": len(data), "references": []})
        else:
            path = clean_path(source_text)
            if not path.is_absolute() or path.name.lower() in {"auth.json", "config.toml"} or path.suffix.lower() in {".sqlite", ".db"}:
                return None
            key = str(path)
            try:
                is_file = path.is_file()
            except OSError:
                return None
            if key in self.assets:
                entry = self.assets[key]
            elif not is_file:
                existing = next((item for item in self.missing if item.get("source") == key), None)
                if existing is None:
                    self.missing.append({"kind": "attachment", "source": key, "reference": reference, "references": [reference], "reason": "Referenced local file does not exist"})
                elif reference not in existing.get("references", []):
                    existing.setdefault("references", [existing["reference"]]).append(reference)
                return None
            else:
                try:
                    relative = path.resolve().relative_to((self.source / "attachments").resolve())
                    target = self.output / "attachments" / relative
                except ValueError:
                    path_key = hashlib.sha256(key.encode("utf-8")).hexdigest()[:12]
                    target = self.output / "attachments" / "external" / (path_key + "-" + path.name)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(str(path), str(target))
                entry = self.assets.setdefault(key, {"source": key, "relative_path": target.relative_to(self.output).as_posix(), "sha256": sha256(target), "bytes": target.stat().st_size, "references": []})
        if reference not in entry["references"]:
            entry["references"].append(reference)
        return self.output / entry["relative_path"]

    def scan_assets(self, value, reference):
        if isinstance(value, dict):
            kind = value.get("type", "")
            if kind in {"localImage", "local_image", "input_image", "image"}:
                for key in ("path", "filePath", "image_url", "url"):
                    candidate = value.get(key)
                    if isinstance(candidate, str) and (candidate.startswith("data:image/") or candidate.startswith("file://") or re.match(r"[A-Za-z]:[\\/]", candidate)):
                        self.add_asset(candidate, reference)
            if kind == "image_url":
                url = value.get("image_url", value.get("url"))
                if isinstance(url, dict):
                    url = url.get("url")
                if isinstance(url, str) and (url.startswith("data:image/") or url.startswith("file://")):
                    self.add_asset(url, reference)
            for child in value.values():
                self.scan_assets(child, reference)
        elif isinstance(value, list):
            for child in value:
                self.scan_assets(child, reference)
        elif isinstance(value, str):
            for match in ATTACHMENT_RE.finditer(value):
                candidate = match.group(0).split(";", 1)[0].strip().rstrip("`)],。 ")
                file_end = re.match(r"(?i)^(.*?\.(?:png|jpe?g|webp|gif|bmp|svg|pdf|txt|md|docx|xlsx|csv|json|yaml|yml|zip|mp4|wav|mp3))(?:\s|[\]\)}`;]|$)", candidate)
                if file_end:
                    candidate = file_end.group(1)
                self.add_asset(candidate, reference)

    def render_part(self, part, markdown_path, reference):
        if isinstance(part, str):
            return part
        if isinstance(part, list):
            return "\n\n".join(self.render_part(child, markdown_path, reference) for child in part)
        if not isinstance(part, dict):
            return json.dumps(part, ensure_ascii=False)
        if isinstance(part.get("text"), str):
            return part["text"]
        kind = part.get("type", "")
        if kind in {"localImage", "local_image", "input_image", "image", "image_url"}:
            candidate = part.get("path", part.get("filePath", part.get("image_url", part.get("url"))))
            if isinstance(candidate, dict):
                candidate = candidate.get("url")
            if isinstance(candidate, str):
                target = self.add_asset(candidate, reference)
                if target:
                    relative = os.path.relpath(str(target), str(markdown_path.parent)).replace("\\", "/")
                    return "![会话中的图片](<" + relative + ">)"
                if candidate.startswith(("https://", "http://")):
                    return "![会话中引用的远程图片](" + candidate + ")"
        return "```json\n" + json.dumps(part, ensure_ascii=False, indent=2) + "\n```"

    def export_thread(self, connection, sid, metadata):
        target = self.output / "chats" / sid
        target.mkdir(parents=True, exist_ok=True)
        write_json(target / "metadata.json", metadata)
        self.scan_assets(metadata.get("title", ""), sid + ":metadata.title")
        history_tables = ("thread_turns", "thread_items", "thread_history_projection_state", "thread_realtime_items")
        table_rows = {}
        for table in history_tables:
            order = "rollout_ordinal" if table != "thread_history_projection_state" else "thread_id"
            table_rows[table] = [dict(row) for row in connection.execute("SELECT * FROM history." + table + " WHERE thread_id=? ORDER BY " + order, (sid,))]
        files = {"thread_turns": "turns.jsonl", "thread_items": "items.jsonl", "thread_history_projection_state": "projection.jsonl", "thread_realtime_items": "realtime-items.jsonl"}
        for table, rows in table_rows.items():
            write_jsonl(target / files[table], rows)
        for table in ("thread_dynamic_tools", "thread_attachments", "thread_read_receipts", "guardian_review_feedback", "session_model_ids"):
            column = "session_id" if table == "session_model_ids" else "thread_id"
            rows = [dict(row) for row in connection.execute("SELECT * FROM " + table + " WHERE " + column + "=?", (sid,))]
            write_jsonl(target / (table + ".jsonl"), rows)
        count = 0
        md = target / "messages.md"
        with md.open("w", encoding="utf-8", newline="\n") as stream:
            stream.write("# " + metadata["title"] + "\n\nChat ID: `" + sid + "`\n\n")
            stream.write("原工作目录：`" + metadata["cwd"] + "`\n\n导出时间（Asia/Shanghai）：" + self.start + "\n\n")
            stream.write("本文逐条保留数据库内的用户与助手消息。工具、推理、文件改动及原始输出完整保存在 items.jsonl；原始 rollout 消息另见 raw-messages。\n\n")
            for row in table_rows["thread_items"]:
                item = json.loads(row["item_json"])
                self.scan_assets(item, sid + ":" + row["item_id"])
                kind = row.get("item_type") or item.get("type")
                if kind not in HUMAN_TYPES:
                    continue
                count += 1
                role = "用户" if kind == "userMessage" else "助手"
                body = item.get("content", []) if kind == "userMessage" else item.get("text", "")
                stream.write("<!-- codex-native-message -->\n## " + str(count) + ". " + role + "\n\n")
                stream.write("时间：" + local_time(row.get("created_at_ms")) + "；ordinal：" + str(row["rollout_ordinal"]) + "；item ID：`" + row["item_id"] + "`\n\n")
                if kind == "agentMessage" and item.get("phase"):
                    stream.write("phase：`" + item["phase"] + "`\n\n")
                stream.write(self.render_part(body, md, sid + ":" + row["item_id"]) + "\n\n")
        actual_counts = {}
        for table, rows in table_rows.items():
            file_path = target / files[table]
            with file_path.open("r", encoding="utf-8") as stream:
                written = sum(1 for line in stream if line.strip())
            actual_counts[table] = {"database_rows": len(rows), "exported_rows": written}
            if written != len(rows):
                raise RuntimeError("Row count mismatch for " + sid + ":" + table)
        expected_messages = sum(1 for row in table_rows["thread_items"] if (row.get("item_type") or json.loads(row["item_json"]).get("type")) in HUMAN_TYPES)
        read_back_count = md.read_text(encoding="utf-8").count("<!-- codex-native-message -->")
        if count != expected_messages or read_back_count != expected_messages:
            raise RuntimeError("Human message count mismatch for " + sid)
        return {"thread_id": sid, "title": metadata["title"], "selection_reason": self.reasons[sid], "counts": actual_counts, "database_human_messages": expected_messages, "markdown_human_messages": read_back_count, "markdown": md.relative_to(self.output).as_posix(), "row_counts_match": True, "message_counts_match": True}

    def decompress(self, source, target):
        target.parent.mkdir(parents=True, exist_ok=True)
        if self.zstd:
            with target.open("wb") as stream:
                process = subprocess.run([self.zstd, "-q", "-d", "-c", str(source)], stdout=stream, stderr=subprocess.PIPE)
            if process.returncode:
                raise RuntimeError("zstd failed for " + str(source) + ": " + process.stderr.decode("utf-8", "replace"))
        else:
            try:
                import zstandard
            except ImportError as exc:
                raise RuntimeError("Install zstd or Python zstandard to export compressed rollouts") from exc
            with source.open("rb") as incoming, target.open("wb") as outgoing:
                zstandard.ZstdDecompressor().copy_stream(incoming, outgoing)

    def raw_message(self, record):
        kind, payload = record.get("type"), record.get("payload", {})
        if not isinstance(payload, dict):
            return None
        if kind == "response_item" and payload.get("type") == "message" and payload.get("role") in {"user", "assistant"}:
            return payload["role"], payload.get("content", []), payload.get("phase") or payload.get("channel")
        if kind == "event_msg" and payload.get("type") in {"user_message", "agent_message"}:
            return ("user" if payload["type"] == "user_message" else "assistant"), payload.get("message", ""), payload.get("phase")
        return None

    def export_raw(self, selected):
        files = []
        for directory in (self.source / "sessions", self.source / "archived_sessions"):
            if directory.exists():
                for source in directory.rglob("rollout-*"):
                    if source.is_file() and (source.name.endswith(".jsonl") or source.name.endswith(".jsonl.zst")) and any(sid in source.name for sid in selected):
                        files.append(source)
        found_ids = set()
        for source in sorted(files):
            relative = source.relative_to(self.source)
            copied = self.output / "raw-rollouts" / relative
            copied.parent.mkdir(parents=True, exist_ok=True)
            before = source.stat()
            captured_at = dt.datetime.now(TZ).isoformat()
            incomplete_tail = 0
            if source.name.endswith(".zst"):
                shutil.copy2(str(source), str(copied))
                plain = self.output / "raw-decompressed" / relative.with_suffix("")
                self.decompress(copied, plain)
            else:
                with source.open("rb") as stream:
                    snapshot = stream.read(before.st_size)
                if snapshot and not snapshot.endswith(b"\n"):
                    complete = snapshot.rfind(b"\n") + 1
                    incomplete_tail = len(snapshot) - complete
                    snapshot = snapshot[:complete]
                copied.write_bytes(snapshot)
                plain = copied
            after = source.stat()
            md = self.output / "raw-messages" / relative.parent / (relative.name + ".messages.md")
            md.parent.mkdir(parents=True, exist_ok=True)
            records, messages, metadata, last_timestamp = 0, 0, None, None
            with plain.open("r", encoding="utf-8") as incoming, md.open("w", encoding="utf-8", newline="\n") as outgoing:
                outgoing.write("# 原始 rollout 消息\n\n原始文件：`" + str(source) + "`\n\n导出时间（Asia/Shanghai）：" + self.start + "\n\n")
                outgoing.write("按原始行号逐条保存用户与助手消息。response_item 与 event_msg 可能记录同一消息的不同事件；此处保留事件原貌，不跨文件删重。全部原始事件见对应 JSONL。\n\n")
                for ordinal, line in enumerate(incoming):
                    if not line.strip():
                        continue
                    record = json.loads(line)
                    records += 1
                    if record.get("timestamp"):
                        last_timestamp = record["timestamp"]
                    if record.get("type") == "session_meta":
                        metadata = record.get("payload", {})
                        found_ids.add(metadata.get("id", ""))
                    message = self.raw_message(record)
                    if not message:
                        continue
                    role, body, phase = message
                    messages += 1
                    reference = str(relative) + ":line:" + str(ordinal + 1)
                    self.scan_assets(body, reference)
                    outgoing.write("<!-- codex-raw-message -->\n## " + str(messages) + ". " + ("用户" if role == "user" else "助手") + "\n\n")
                    outgoing.write("原始行号：" + str(ordinal + 1) + "；时间：" + str(record.get("timestamp", "unknown")) + "；事件类型：`" + record.get("type", "") + "`\n\n")
                    if phase:
                        outgoing.write("phase/channel：`" + str(phase) + "`\n\n")
                    outgoing.write(self.render_part(body, md, reference) + "\n\n")
            if md.read_text(encoding="utf-8").count("<!-- codex-raw-message -->") != messages:
                raise RuntimeError("Raw message count mismatch for " + str(source))
            info = {"source": str(source), "source_relative_path": relative.as_posix(), "packaged_path": copied.relative_to(self.output).as_posix(), "decompressed_path": plain.relative_to(self.output).as_posix(), "markdown_path": md.relative_to(self.output).as_posix(), "session_metadata": metadata, "source_snapshot_captured_at": captured_at, "last_record_timestamp": last_timestamp, "source_bytes_at_snapshot": before.st_size, "copied_bytes": copied.stat().st_size, "sha256": sha256(copied), "decompressed_sha256": sha256(plain), "json_records": records, "raw_user_assistant_events": messages, "markdown_message_count": messages, "excluded_incomplete_tail_bytes": incomplete_tail, "source_changed_during_capture": before.st_size != after.st_size or before.st_mtime_ns != after.st_mtime_ns}
            self.raw_records.append(info)
        for sid, metadata in selected.items():
            original = clean_path(metadata["rollout_path"])
            alternatives = [str(original), str(original) + ".zst"]
            matched = any(sid in entry["source"] for entry in self.raw_records)
            if not matched:
                self.missing.append({"kind": "rollout", "thread_id": sid, "source": str(original), "checked_paths": alternatives, "reason": "No selected rollout or compressed counterpart found"})

    def resolve_known_attachment_reference(self):
        # Verified against this chat's user image reference at raw line 4055,
        # view_image arguments at 4061, and assistant handoff typo at 4073.
        bad = r"C:\Users\HD\.codex\attachments\955f54ce-f3ed-457f-ab1-45e78b019d34\image-1.jpg"
        good = r"C:\Users\HD\.codex\attachments\955f54ce-f3ed-457f-aab1-45e78b019d34\image-1.jpg"
        asset = self.assets.get(good)
        if not asset or not (self.output / asset["relative_path"]).is_file():
            return
        for entry in self.missing:
            same_chat_evidence = any("019fa3b0-4ea3-7d13-9ea1-79f53b846c5e" in reference for reference in asset.get("references", []))
            if entry.get("source") == bad and same_chat_evidence:
                entry["resolved"] = True
                entry["resolved_source"] = good
                entry["resolved_packaged_path"] = asset["relative_path"]
                entry["resolution_record"] = "attachment-reference-resolutions.json"
                self.resolutions.append({"original_source": bad, "original_reference": entry["reference"], "all_reference_locations": entry.get("references", [entry["reference"]]), "resolved_source": good, "resolved_packaged_path": asset["relative_path"], "sha256": asset["sha256"], "bytes": asset["bytes"], "basis": "The original user image reference at rollout line 4055 and view_image arguments at line 4061 use UUID component aab1. The assistant-generated handoff at line 4073 contains the ab1 spelling error. The correctly referenced original image still exists and was copied. All conversation text remains unchanged."})

    def run(self):
        connection = self.open_snapshot()
        try:
            selected, edges = self.select_threads(connection)
            schemas = {}
            for database, tables in (("main", ("threads", "thread_spawn_edges", "thread_dynamic_tools", "thread_attachments", "thread_read_receipts", "guardian_review_feedback", "session_model_ids")), ("history", ("thread_turns", "thread_items", "thread_history_projection_state", "thread_realtime_items"))):
                schemas[database] = {table: connection.execute("SELECT sql FROM " + database + ".sqlite_master WHERE type='table' AND name=?", (table,)).fetchone()[0] for table in tables}
            write_json(self.output / "schema.json", schemas)
            write_jsonl(self.output / "thread-relations.jsonl", edges)
            reports = [self.export_thread(connection, sid, metadata) for sid, metadata in sorted(selected.items(), key=lambda pair: (pair[1].get("created_at", 0), pair[0]))]
        finally:
            connection.rollback()
            connection.close()
        self.export_raw(selected)
        self.resolve_known_attachment_reference()
        write_json(self.output / "attachments-map.json", list(self.assets.values()))
        write_json(self.output / "missing-files.json", self.missing)
        write_json(self.output / "attachment-reference-resolutions.json", self.resolutions)
        write_json(self.output / "raw-rollouts-map.json", self.raw_records)
        index = {"format_version": 1, "snapshot_started_at": self.start, "export_completed_at": dt.datetime.now(TZ).isoformat(), "timezone": "Asia/Shanghai", "source_codex_home": str(self.source), "source_databases_read_only": True, "global_databases_or_auth_copied": False, "selected_threads": reports, "raw_rollout_count": len(self.raw_records), "raw_json_record_count": sum(row["json_records"] for row in self.raw_records), "raw_user_assistant_event_count": sum(row["raw_user_assistant_events"] for row in self.raw_records), "native_user_assistant_message_count": sum(row["database_human_messages"] for row in reports), "copied_attachment_count": len(self.assets), "missing_reference_count": len(self.missing), "all_row_counts_match": all(row["row_counts_match"] for row in reports), "all_message_counts_match": all(row["message_counts_match"] for row in reports), "notes": ["This is a scoped file export, not a supported desktop database import package.", "Original raw events and source table rows are preserved without truncation.", "Native transcripts and raw-event transcripts can overlap; counts are reported separately.", "The active packaging conversation is a point-in-time snapshot; future messages are not claimed to be included."]}
        write_json(self.output / "export-report.json", index)
        index["resolved_reference_count"] = len(self.resolutions)
        index["unresolved_missing_reference_count"] = sum(1 for entry in self.missing if not entry.get("resolved"))
        write_json(self.output / "export-report.json", index)
        readme = ["# StockShotManager Codex 会话导出", "", "请在新电脑安装并登录 Codex，打开包内 StockShotManager 项目，然后先阅读包根 START_HERE.md 和 CONTINUE_IN_CODEX.md。", "", "此目录保留当前可取得的相关会话原文。chats/<chat-id>/messages.md 是数据库中所有用户与助手消息的逐条记录；items.jsonl 保留工具、输出、推理、修改及消息原始 JSON 字段。raw-rollouts 保留原始 .jsonl/.jsonl.zst，压缩记录同时提供解压后的 .jsonl；raw-messages 提供原始事件中的用户与助手消息。", "", "数据库记录和原始事件可能重复。保留事件原貌，没有以摘要替换原文，也没有跨会话删除继承历史。工具调用、输出和全部轮次按 ordinal 保存在 JSONL。", "", "本次快照时间（Asia/Shanghai）：" + self.start, "", "逐会话条数和一致性校验见 export-report.json；源路径映射见 raw-rollouts-map.json、attachments-map.json；缺失的本地引用见 missing-files.json。", "", "Codex 桌面 app 的数据库结构与版本有关；本导出没有覆盖或导入另一电脑的全局数据库。当前安装的 CLI 0.146.0 的 --help 确认 `codex resume <SESSION_ID> --cd <新项目路径>` 与 `codex resume --all` 的语法，但这不能证明跨电脑复制 paginated 历史后桌面会自动显示或恢复它。推荐在新电脑创建项目聊天，通过本包的接续说明和原文记录继续。", "", "产品参考：[Codex CLI reference](https://developers.openai.com/codex/cli/reference/)；[Codex CLI features](https://developers.openai.com/codex/cli/features/)。本机帮助输出另存于 cli-help-evidence.txt。", "", "| Chat ID | 标题（原样） | 用户/助手消息 |", "|---|---|---|", ]
        for row in reports:
            title = row["title"].replace("|", "\\|").replace("\r", "").replace("\n", "<br>")
            readme.append("| [" + row["thread_id"] + "](" + row["markdown"] + ") | " + title + " | " + str(row["database_human_messages"]) + " |")
        readme.extend(["", "附件引用解析见 [attachment-reference-resolutions.json](attachment-reference-resolutions.json)。该记录保留旧助手交接中的错误路径并指向已打包的正确原图；会话原文保持原样。当前已解析引用 " + str(len(self.resolutions)) + " 条，尚缺引用 " + str(index["unresolved_missing_reference_count"]) + " 条。"])
        (self.output / "README.md").write_text("\n".join(readme) + "\n", encoding="utf-8")
        print(json.dumps({"output": str(self.output), "selected_threads": len(reports), "raw_rollouts": len(self.raw_records), "native_human_messages": index["native_user_assistant_message_count"], "raw_human_events": index["raw_user_assistant_event_count"], "attachments": len(self.assets), "resolved_reference_count": index["resolved_reference_count"], "unresolved_missing_reference_count": index["unresolved_missing_reference_count"], "all_row_counts_match": index["all_row_counts_match"], "all_message_counts_match": index["all_message_counts_match"], "snapshot_started_at": self.start, "export_completed_at": index["export_completed_at"]}, ensure_ascii=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex-home", type=pathlib.Path, default=pathlib.Path(os.environ.get("CODEX_HOME", str(pathlib.Path.home() / ".codex"))))
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--zstd", help="Path to zstd executable; optional if zstd is on PATH or zstandard is installed")
    parser.add_argument("--include", action="append", default=[], help="Additional explicitly requested chat UUID")
    options = parser.parse_args()
    Exporter(options.codex_home, options.output, options.zstd, options.include).run()


if __name__ == "__main__":
    main()
