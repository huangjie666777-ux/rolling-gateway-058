# 签名离线资料包交接（Offline Signed Package）

用于断网现场在发布端与目标端之间交接可验证来源的维修资料。基于 Java 17 的
Ed25519 签名（`java.security`），不引入额外加密库。

## 包格式（公开）

ZIP（UTF-8 条目名）内固定包含：

| 条目 | 说明 |
| --- | --- |
| `manifest.json` | UTF-8 JSON，签名对象就是它的原始字节 |
| `manifest.sig`  | 对 manifest 原始字节的 Ed25519 签名（原始签名字节，非 hex/base64） |
| `README.md`     | 随包分发的人类可读格式说明 |
| `docs/<id>.json` | 逐文档 UTF-8 JSON：`{"id","title","body"}`，按 id 排序 |

manifest 结构：

```json
{
  "version": 1,
  "keyId": "publisher-key-id",
  "documents": [
    {"id": "M-001", "path": "docs/M-001.json", "bytes": 123, "sha256": "<小写hex>"}
  ]
}
```

`bytes` 为实际未压缩字节数，`sha256` 为文档 JSON 文件字节的 SHA-256。
条目按 id 去重并升序。包内不包含私钥或公钥。

## 发布端

```java
PackageExporter.export(index, query, Path.of("kb.zip"), keyId, ed25519PrivateKey);
```

- 在一个快照 `SearchSession` 内翻页取完全部命中文档（id/标题/正文），导出期间
  并发提交不会混入。
- ZIP 先写同目录临时文件，成功后原子 `ATOMIC_MOVE`；失败不破坏既有目标文件。

## 接收端

```java
TrustedKeys keys = TrustedKeys.of(Map.of(keyId, ed25519PublicKey)); // 调用方提供
VerifiedPackage verified = PackageVerifier.verify(Path.of("kb.zip"), keys); // 只读验证
index.importPackage(Path.of("kb.zip"), keys, ImportMode.REJECT);     // 或 OVERWRITE
```

- 公钥只来自调用方提供的 keyId 表，包内任何公钥信息都不被信任。
- 先验签再使用清单字段；仅接受 `version=1`，未知 keyId 一律拒绝。
- 拒绝：长度/摘要不符、重复 id 或 ZIP 条目、缺失或未声明文件、非法 JSON、
  绝对路径 / `..` 穿越 / 反斜杠 / 空路径段 / 目录条目。
- 资源上限 `PackageLimits(maxDocuments, maxEntryBytes, maxTotalBytes, maxZipEntries)`
  在流式读取实际膨胀字节时执行，默认 10 万篇、单条 16 MiB、总量 512 MiB；
  不信任 ZIP 头或清单声明的大小。
- 导入先完成全部验证，再在 `ManualIndex` 的写锁内做冲突检查并一次性提交：
  `REJECT` 模式任一 id 已存在则整包拒绝；`OVERWRITE` 模式同 id 覆盖且不产生重复，
  其他文档保留。并发写入在同一写锁 + 最新提交快照上检查冲突，旧搜索会话继续
  停留在原快照；任何失败都发生在提交前，不残留会被后续提交带入的文档。

## 密钥与资源生命周期

- 私钥只存在于发布端，签名时在内存中使用，永不写入资料包；公钥分发与
  keyId 登记由运维流程负责，SDK 不提供信任根。
- `ManualIndex` 与 `SearchSession` 均为 `AutoCloseable`，建议 try-with-resources；
  验证本身不占用索引资源，导入成功随 Lucene 提交持久化。
