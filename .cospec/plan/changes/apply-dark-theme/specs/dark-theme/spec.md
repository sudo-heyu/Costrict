## ADDED Requirements

### Requirement: 深色主题布局
系统应在所有Activity和Fragment布局中应用深灰色(#212121)背景主题。

#### Scenario: Worker端界面显示深灰色背景
- **WHEN** 用户打开Worker端的任意Activity或Fragment
- **THEN** 界面应显示深灰色(#212121)背景，文本为白色

#### Scenario: 监管端界面显示深灰色背景
- **WHEN** 用户打开监管端的任意Activity或Fragment
- **THEN** 界面应显示深灰色(#212121)背景，文本为白色

### Requirement: 白色背景移除
系统应移除所有布局文件中硬编码的白色背景属性。

#### Scenario: 登录界面深灰色背景
- **WHEN** 用户打开登录界面
- **THEN** 不应包含`@color/white`、`@android:color/white`或`#F5F5F5`等白色背景属性

#### Scenario: 主界面深灰色背景
- **WHEN** 用户打开主界面
- **THEN** 界面应使用深灰色(#212121)背景

### Requirement: 边框颜色适配
系统应更新深色背景下的边框颜色以确保可见性。

#### Scenario: 状态框边框适配
- **WHEN** 显示状态框时
- **THEN** 边框颜色应为浅灰色以确保在深灰色背景下可见

#### Scenario: 设备列表边框适配
- **WHEN** 显示设备列表时
- **THEN** 边框颜色应为浅灰色以确保在深灰色背景下可见