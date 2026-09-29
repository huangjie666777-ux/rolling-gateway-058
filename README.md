# Rolling Gateway

支持滚动发布的 Go HTTP 网关。代理与管理服务使用独立端口，按节点在途数调度，并在删除或改地址时保留旧代长请求直到响应体结束。

## 特性

- 管理接口整表替换上游：唯一 `id`、`http://host:port` 地址、正整数 `limit`。
- 配置先完整校验，再原子发布并递增 `version`；非法请求保留旧配置。
- 当前节点中选择 `inflight < limit` 且在途最少者，相同在途按 `id` 升序；选择和占用在同一临界区完成。
- 无容量立即返回 `503`，不排队；降低上限不取消已接收请求，但不会继续超额接收。
- 方法、路径、查询、请求体、响应状态和响应正文透传，逐跳头由 `httputil.ReverseProxy` 清理。
- 上传、下载和分块响应均流式处理；在途计数在响应体复制完成或客户端取消后才释放。
- 删除或修改地址的节点进入旧代 `draining`，不接新流量；旧请求完成后自动回收空闲连接。
- 收到 `SIGINT`/`SIGTERM` 后立即停止接收代理请求，在 `DRAIN_TIMEOUT` 内等待，超时取消剩余请求。

## 文件职责

- `main.go`：环境变量与进程入口。
- `config.go`：上游 JSON、地址和容量校验。
- `pool.go`：原子发布、容量调度、代际节点、排空和连接回收。
- `proxy.go`：HTTP 反向代理和流式转发。
- `admin.go`：Chi 管理路由。
- `server.go`：代理/管理 HTTP 服务、信号处理和优雅退出。
- `cmd/upstream/main.go`：本机演示上游。

## 配置

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PROXY_ADDR` | `:8080` | 代理监听地址 |
| `ADMIN_ADDR` | `:8081` | 管理监听地址 |
| `DRAIN_TIMEOUT` | `30s` | 退出时等待长请求排空的最长时间 |
| `UPSTREAMS` | 空 | 启动初始上游，格式为 `http://host:port:limit,http://host:port:limit`；也可启动后通过管理 API 设置 |

## 构建与测试

```bash
go test -race ./...
go vet ./...
go build -o bin/gateway .
go build -o bin/upstream ./cmd/upstream
```

## 本机启动

启动两个示例上游：

```bash
UPSTREAM_NAME=upstream-a UPSTREAM_ADDR=127.0.0.1:9001 ./bin/upstream
UPSTREAM_NAME=upstream-b UPSTREAM_ADDR=127.0.0.1:9002 ./bin/upstream
```

另开终端启动网关：

```bash
PROXY_ADDR=127.0.0.1:8080 \
ADMIN_ADDR=127.0.0.1:8081 \
DRAIN_TIMEOUT=10s \
./bin/gateway
```

## 管理接口

发布整表配置：

```bash
curl -sS -X PUT http://127.0.0.1:8081/admin/upstreams \
  -H 'Content-Type: application/json' \
  -d '[
    {"id":"a","address":"http://127.0.0.1:9001","limit":1},
    {"id":"b","address":"http://127.0.0.1:9002","limit":1}
  ]'
```

查询当前配置：

```bash
curl -sS http://127.0.0.1:8081/admin/config
```

查询当前代和旧代节点状态：

```bash
curl -sS http://127.0.0.1:8081/admin/nodes
```

状态字段：

- `state=active`：当前配置中的节点。
- `state=draining`：被删除或改地址的旧代节点。
- `inflight`：尚未完成请求体/响应体的在途请求数。
- `generation`：发布版本，便于区分同 id 的新旧节点。

## 转发与滚动摘流

普通请求和请求体透传：

```bash
curl -sS -X POST 'http://127.0.0.1:8080/echo?x=1' --data 'hello'
```

分块流式响应：

```bash
curl --no-buffer http://127.0.0.1:8080/stream
```

开两个长请求各占满一个节点：

```bash
curl --no-buffer http://127.0.0.1:8080/slow
curl --no-buffer http://127.0.0.1:8080/slow
```

在长请求仍传输时，将节点 `a` 从整表移除：

```bash
curl -sS -X PUT http://127.0.0.1:8081/admin/upstreams \
  -H 'Content-Type: application/json' \
  -d '[{"id":"b","address":"http://127.0.0.1:9002","limit":1}]'
```

此时查询能看到旧代 `a`：

```json
[
  {"id":"a","address":"http://127.0.0.1:9001","limit":1,"inflight":1,"generation":2,"state":"draining"},
  {"id":"b","address":"http://127.0.0.1:9002","limit":1,"inflight":1,"generation":2,"state":"active"}
]
```

两个节点都被占用时，新请求立即得到 `503 Service Unavailable`，不会排队。旧代长请求结束后，节点 `a` 从状态中消失且空闲连接被回收，后续请求只发往 `b`。

## 优雅退出

```bash
kill -TERM "$(pgrep -f 'bin/gateway')"
```

代理监听会立即关闭；管理接口在排空期间仍可查询。长请求最多继续至 `DRAIN_TIMEOUT`，到期后取消上游请求并退出。
