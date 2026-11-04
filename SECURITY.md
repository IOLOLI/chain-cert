# Security Policy

## 公共仓库约定

- 仓库只保留源码、合约、SQL、示例配置与文档。
- 不提交真实数据库口令、JWT 密钥、AES 主密钥、机构私钥、节点证书、生产 IP、域名或部署账号。
- 本地配置请放在 `.env`、`backend/config/`、`backend/sdk/conf/` 等已被 `.gitignore` 忽略的位置。
- 部署前请复制 `.env.example`，替换所有 `change-me` / `ChangeMe_*` 占位符。

## 如果密钥曾经进入 Git 历史

仅删除当前文件并不能消除历史泄露。请立即：

1. 轮换数据库密码、JWT 密钥、AES 主密钥、ADMIN 私钥；
2. 在链上转移/撤销受影响账户的权限；
3. 清理 Git 历史（例如使用 `git filter-repo`）后强制推送；
4. 检查 CI/CD、制品包、日志和备份中是否仍有旧值。

## 报告问题

发现安全问题请勿公开提交 Issue，请通过仓库维护者提供的私密渠道联系。