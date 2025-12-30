# 变更：应用黑底白字深色主题

## 原因
当前应用虽然主题配置了黑底白字，但多数布局文件硬编码了白色背景，导致实际显示为白底白字或白底黑字，与预期的黑底白字主题不符。

## 变更内容
- 在colors.xml中添加深灰色颜色定义`background_dark` (#212121)
- 将Worker端和监管端的所有Activity和Fragment布局文件的背景色从白色改为深灰色#212121
- 移除或替换硬编码的白色背景引用（`@color/white`、`@android:color/white`、`#F5F5F5`等）
- 更新相关文本颜色以确保在深色背景下清晰可见

## 影响
- **受影响的规范**：UI主题和颜色规范
- **受影响的代码**：
    - `app/src/main/res/values/colors.xml`: 添加深灰色颜色定义`background_dark` (#212121)
    - `app/src/main/res/layout/activity_login_worker.xml`: 移除白色背景属性，更新文本颜色
    - `app/src/main/res/layout/activity_login_regulator.xml`: 移除白色背景属性
    - `app/src/main/res/layout/activity_main_worker.xml`: 更新底部导航背景色为深灰色
    - `app/src/main/res/layout/activity_main_regulator.xml`: 底部导航已使用主题属性（无需修改）
    - `app/src/main/res/layout/fragment_detection.xml`: 移除白色背景
    - `app/src/main/res/layout/fragment_monitoring.xml`: 更新背景色为深灰色，工具栏背景色为深灰色
    - `app/src/main/res/layout/fragment_monitoring_worker.xml`: 移除白色背景，更新分割线颜色
    - `app/src/main/res/layout/fragment_profile.xml`: 更新背景色和卡片背景色为深灰色
    - `app/src/main/res/layout/fragment_profile_worker.xml`: 移除白色背景
    - `app/src/main/res/layout/fragment_work_record.xml`: 更新背景色和工具栏背景色为深灰色，更新标题文本颜色
    - `app/src/main/res/drawable/device_list_border.xml`: 更新边框颜色为浅灰色
    - `app/src/main/res/drawable/status_box_border.xml`: 更新边框颜色为浅灰色