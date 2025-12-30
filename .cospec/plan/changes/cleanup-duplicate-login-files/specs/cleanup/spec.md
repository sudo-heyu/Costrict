## ADDED Requirements

### Requirement: 删除重复的登录和开屏Activity
系统应删除operator和regulator目录下重复的登录和开屏Activity源代码文件。

#### Scenario: 删除operator目录下的重复Activity
- **WHEN** 系统进行代码清理
- **THEN** `app/src/main/java/com/heyu/safetybelt/operator/activity/LoginActivity.kt` 应被删除
- **THEN** `app/src/main/java/com/heyu/safetybelt/operator/activity/SplashActivity.kt` 应被删除

#### Scenario: 删除regulator目录下的重复Activity
- **WHEN** 系统进行代码清理
- **THEN** `app/src/main/java/com/heyu/safetybelt/regulator/activity/LoginActivity.kt` 应被删除
- **THEN** `app/src/main/java/com/heyu/safetybelt/regulator/activity/SplashActivity.kt` 应被删除

### Requirement: 删除重复的布局文件
系统应删除operator和regulator目录下重复的登录和开屏界面布局文件。

#### Scenario: 删除operator目录下的重复布局文件
- **WHEN** 系统进行资源清理
- **THEN** `app/src/main/res/layout/activity_login_worker.xml` 应被删除
- **THEN** `app/src/main/res/layout/activity_splash_worker.xml` 应被删除

#### Scenario: 删除regulator目录下的重复布局文件
- **WHEN** 系统进行资源清理
- **THEN** `app/src/main/res/layout/activity_login_regulator.xml` 应被删除
- **THEN** `app/src/main/res/layout/activity_splash_regulator.xml` 应被删除

### Requirement: 应用深色主题到登录界面
系统应将common目录下的登录界面（activity_login.xml）应用深色主题。

#### Scenario: 登录界面深色背景
- **WHEN** 用户打开登录界面
- **THEN** 界面不应包含白色背景属性
- **THEN** 界面应使用深灰色背景以确保深色主题一致性