把 FISCO BCOS 节点生成的 SDK 证书放到这个目录，共三个文件：

    ca.crt
    sdk.crt
    sdk.key

来源：build_chain.sh 生成链后，节点目录里的 sdk 文件夹，例如

    <链目录>/nodes/127.0.0.1/sdk/ca.crt
    <链目录>/nodes/127.0.0.1/sdk/sdk.crt
    <链目录>/nodes/127.0.0.1/sdk/sdk.key

放好之后启动后端，访问 GET /api/chain/status 应看到 ready=true。
若显示 "SDK 配置文件不存在" 或 TLS 相关错误，检查：
  1) sdk/config.toml 里的 certPath 是否指向本目录（默认 conf）；
  2) 三个证书文件是否齐全、文件名是否完全一致；
  3) sdk/config.toml 里 peers 的端口是否为节点的 channel 端口（默认 20200）。
