# SG Delivery Android: Permanent Release Signing (Windows)

The signing key is created **by the repository owner**, never by ChatGPT or GitHub. One keystore must be preserved for every future Android release. Do not commit, upload to a PR, or share keystore passwords in chat.

## 1. Generate the permanent keystore on Windows

Install JDK 17 if needed; check in PowerShell: \`keytool -help\`. If missing, install a trusted JDK 17 (for example Eclipse Temurin) and reopen PowerShell. If \`keytool\` is not on PATH, use its full path under the JDK \`bin\` directory.

In **PowerShell**:

\`\`\`powershell
$folder = Join-Path $HOME 'SGDeliverySigning'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
Set-Location $folder
keytool -genkeypair -v -keystore sg-delivery-release.jks -storetype JKS -alias sgdelivery -keyalg RSA -keysize 3072 -validity 10000
\`\`\`

Choose a **strong and unique** keystore password; the tool may ask for a separate private-key password (press Enter to use the same, if offered). The remaining certificate identification fields can identify the app owner/company. Keep a secure record of:

- The file \`sg-delivery-release.jks\`
- Keystore password
- Key alias: \`sgdelivery\`
- Key password

**Back up the file and passwords offline**, in separate safe locations. The key must never be regenerated as a substitute for the same app: Android will reject normal updates signed with a different certificate. Existing **debug-signed** installations cannot be updated in place using the new release certificate. Back up delivery records **before uninstalling** any existing debug app. Reinstallation can delete local app records.

Optional verification (enter the keystore password when prompted):

\`\`\`powershell
keytool -list -v -keystore "$HOME\SGDeliverySigning\sg-delivery-release.jks" -alias sgdelivery
\`\`\`

Note the SHA-256 **certificate fingerprint** (public identity) for later comparison. Never copy the keystore or passwords into a public issue or README.

## 2. Configure GitHub Actions secrets

In GitHub, open **SG-Delivery-Planner → Settings → Secrets and variables → Actions → New repository secret**. Create exactly four repository secrets:

| Secret name | Value |
|---|---|
| \`SG_DELIVERY_KEYSTORE_BASE64\` | Base64 encoding of the exact .jks file bytes |
| \`SG_DELIVERY_KEYSTORE_PASSWORD\` | Keystore password you created |
| \`SG_DELIVERY_KEY_ALIAS\` | \`sgdelivery\` |
| \`SG_DELIVERY_KEY_PASSWORD\` | Private-key password, often same as keystore password |

To encode the keystore with PowerShell and copy **only the Base64 value** to the clipboard:

\`\`\`powershell
$file = Join-Path $HOME 'SGDeliverySigning\sg-delivery-release.jks'
[Convert]::ToBase64String([IO.File]::ReadAllBytes($file)) | Set-Clipboard
\`\`\`

Paste this into the GitHub secret \`SG_DELIVERY_KEYSTORE_BASE64\`, save the secret, then clear the clipboard:

\`\`\`powershell
Set-Clipboard -Value ''
\`\`\`

Never paste this Base64 into the repository files. Base64 is **encoding, not encryption**; treat it as a secret. Make sure the repository and GitHub account are secured using two-factor authentication.

## 3. Build the real signed release APK

After release-signing setup is merged to \`main\` and all four GitHub secrets are saved:

1. Navigate to [Build Signed SG Delivery Release APK](https://github.com/izzyanfenester-collab/SG-Delivery-Planner/actions/workflows/build-release-apk.yml).
2. Click **Run workflow**, select **main**, and run.
3. Wait for every job step to show green, including **Confirm release APK is signed and verify its identity**.
4. Download artifact **SG-Delivery-Official-Signed-Release-APK**. The artifact is a ZIP containing \`app-release.apk\`.
5. Verify Android version and SHA-256 fingerprint, install on a test device, and validate backup/restore and delivery history before wider distribution.

Version of initial release signing configuration: **1.3 (versionCode 4)**. Future versions must use a **strictly increasing** versionCode and **exactly the same keystore**.

## Security and limitations

- The workflow is **manual dispatch only** and runs **on main**. It does not expose secrets to PR builds.
- The workflow first runs unit tests and Android lint, reconstructs the keystore in GitHub's temporary runner directory, builds, uses Android's \`apksigner verify --print-certs\`, uploads **only the APK**, then deletes the temporary keystore.
- Repository secrets cannot be read back after they have been saved. The original keystore backup is vital.
- A GitHub-produced debug APK is **not** a signed production release even if built from \`main\`.
- APK signatures are not Google Play approval. External APK installs may still prompt Android security checks.
- If old Android installations have only been signed with a debug certificate, moving to release signing normally requires backup → uninstall debug app → install release app → restore backed-up data. Never uninstall first.
- App package ID is unchanged: \`com.izzyan.sgdeliveryplanner\`.
