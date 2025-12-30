# 变更：清理重复的登录和开屏界面文件并修复登录界面背景色

## 原因
项目中存在重复的登录和开屏界面文件：
- common目录下的Activity是统一使用的
- operator和regulator目录下的Activity是多余的，不应存在
- common目录下的登录界面（activity_login.xml）背景色仍为白色，未应用深色主题

## 变更内容
- 删除operator目录下的 LoginActivity.java/.kt 和 SplashActivity.java/.kt
- 删除operator目录下的布局文件 activity_login_worker.xml 和 activity_splash_worker.xml
- 删除regulator目录下的 LoginActivity.java/.kt 和 SplashActivity.java/.kt
- 删除regulator目录下的布局文件 activity_login_regulator.xml 和 activity_splash_regulator.xml
- 修复common目录下的登录界面（activity_login.xml）：移除白色背景，应用深灰色背景

## 影响
- **受影响的规范**：无
- **受影响的代码**：
    - **删除文件**：
        - `app/src/main/java/com/heyu/safetybelt/operator/activity/LoginActivity.kt`: 删除
        - `app/src/main/java/com/heyu/safetybelt/operator/activity/SplashActivity.kt`: 删除
        - `app/src/main/res/layout/activity_login_worker.xml`: 删除
        - `app/src/main/res/layout/activity_splash_worker.xml`: 删除
        - `app/src/main/java/com/heyu/safetybelt/regulator/activity/LoginActivity.kt`: 删除
        - `app/src/main/java/com/heyu/safetybelt/regulator/activity/SplashActivity.kt`: 删除
        - `app/src/main/res/layout/activity_login_regulator.xml`: 删除
        - `app/src/main/res/layout/activity_splash_regulator.xml`: 删除
    - **修改文件**：
        - `app/src/main/res/layout/activity_login.xml`: 移除白色背景，应用深灰色背景