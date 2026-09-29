# commission-hub

## 部署

需要 Java 21 或更高版本

```bash
java -jar commission-hub.jar
```

首次启动会在当前目录生成 `config.properties` 和 `data/`, 控制台会打印管理员 `admin` 的初始密码, 只显示一次

## 构建

```bash
./gradlew build
```

产物在 `build/libs/commission-hub.jar`
