# 签名配置说明

## 生成签名密钥（如果还没有）

```bash
# 在项目根目录执行
keytool -genkey -v \
  -keystore lsposed_module/magicwindow.keystore \
  -alias magicwindow \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass magicwindow123 \
  -keypass magicwindow123 \
  -dname "CN=MagicWindow, OU=Development, O=LSPosed, L=Unknown, ST=Unknown, C=CN"
```

## 配置 GitHub Secrets

在 GitHub 仓库设置中添加以下 Secrets：

### 1. KEYSTORE_BASE64

将 keystore 文件转换为 Base64 格式：

```bash
# Linux/Mac
base64 -i lsposed_module/magicwindow.keystore

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("lsposed_module\magicwindow.keystore"))
```

然后将输出的 Base64 字符串添加到 GitHub Secrets：
- 进入仓库 → Settings → Secrets and variables → Actions
- 点击 "New repository secret"
- Name: `KEYSTORE_BASE64`
- Value: 粘贴上面的 Base64 字符串

## 测试签名配置

本地测试构建 Release 版本：

```bash
cd lsposed_module
./gradlew clean assembleRelease
```

构建产物位置：
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## 安全提示

⚠️ **不要将 keystore 文件直接提交到 Git！**

已配置 `.gitignore` 忽略 keystore 文件：
```
*.keystore
*.jks
```

请确保通过 GitHub Secrets 管理签名密钥。
