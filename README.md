# 口袋实验室

实验室预约、器材借还与学习资源管理系统的技术分享源码。

- `pocket-lab-frontend` 使用 Vue 3、uni-app 和 Vite。页面按用户角色呈现功能，封装了请求、表单校验、批量导入、扫码和状态反馈。
- `pocket-lab-backend` 使用 Java 21 与 Spring Boot，包含权限校验、预约和借还状态流转、库存校验及幂等处理。接口定义见 `src/main/resources/openapi.json`。

前端可以独立编译。进入 `pocket-lab-frontend` 后运行以下命令。

```sh
npm ci
npm test
npm run build:h5
```

进入 `pocket-lab-backend`，使用 Java 21 运行 `mvn test` 可编译服务端代码并执行随包提供的策略单元测试。此分享版用于阅读、构建和验证源码，完整业务联调需要自行准备独立演示环境。

将此目录的内容作为新仓库的根目录即可，根目录包含本说明和两个子项目。
