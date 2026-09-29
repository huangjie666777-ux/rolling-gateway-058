# Rolling HTTP Gateway

支持滚动发布的 HTTP 网关。数据面和管理面分端口监听，管理接口整表原子替换上游；长请求在后端切换期间继续完成，新流量只进入未满的当前节点。

## 功能

- 整表替换上游：唯一 `id`、仅允许 `http://host:port` 地址、正整数 `concurrency`。
- 原子发布：校验失败保留旧配置和版本；成功后版本递增。
- 容量调度：在未满节点中选择在途数最少者，相同则按 `id` 升序；选择与占用同一把锁完成，无容量立即返回 `503`。
- 长请求保留：在途计数持续到响应体完整结束或请求取消；降低上限不取消旧请求，只停止继续接收。
- 滚动排空：同 `id` 同地址更新沿用计数；删除或改地址的旧节点进入 `draining`，归零后从状态表移除并关闭空闲连接。
- 流式转发：请求体、响应体和分块响应均边读边写；客户端断开会取消上游请求；上游连接失败返回 `502`。
- 优雅退出：收到 `SIGINT`/`SIGTERM` 后先停止接收新代理请求，在期限内等待排空，超时取消剩余请求。

## 构建

```bash
go test ./...
go build -o dist/gateway .
go build -o dist/example-upstream ./cmd/example-upstream
```

## 启动

```bash
./dist/example-upstream -addr :9001 -id blue
./dist/example-upstream -addr :9002 -id green
./dist/gateway -proxy-addr :8080 -admin-addr :8081 -drain-timeout 30s
```

也可以用环境变量配置：`PROXY_ADDR`、`ADMIN_ADDR`、`DRAIN_TIMEOUT`。

## 管理接口

整表替换上游：

```bash
curl -sS -X PUT http://127.0.0.1:8081/admin/upstreams \
  -H 'Content-Type: application/json' \
  -d '[
    {"id":"blue","address":"http://127.0.0.1:9001","concurrency":2},
    {"id":"green","address":"http://127.0.0.1:9002","concurrency":2}
  ]'
```

查询配置、当前节点和排空节点：

```bash
curl -sS http://127.0.0.1:8081/admin/upstreams
```

字段含义：`current` 是可接新流量的当前代；`draining` 是仍有在途请求的旧代；`in_flight` 为 0 后旧代节点回收，不再显示。

## 转发示例

```bash
curl -sS -X POST 'http://127.0.0.1:8080/echo?hello=world' \
  -H 'Content-Type: text/plain' \
  --data 'rolling'
curl -sS -N http://127.0.0.1:8080/stream
```

## 摘流和排空

先发起一个需要 2 秒左右完成的长请求：

```bash
curl -sS -N http://127.0.0.1:8080/slow &
```

在请求完成前发布只包含另一个节点的新配置：

```bash
curl -sS -X PUT http://127.0.0.1:8081/admin/upstreams \
  -H 'Content-Type: application/json' \
  -d '[{"id":"green","address":"http://127.0.0.1:9002","concurrency":2}]'
curl -sS http://127.0.0.1:8081/admin/upstreams
```

响应中会看到被删除的 `blue` 节点位于 `draining` 且 `in_flight` 保持到响应结束；请求结束后再查询，该旧代节点消失。

## 退出

```bash
kill -TERM <gateway-pid>
```

网关立即停止接收新代理请求，等待最长 `drain-timeout`；超时后取消仍未完成的请求并退出。
