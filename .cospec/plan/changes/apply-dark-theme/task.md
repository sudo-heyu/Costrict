## 1. 目标
为Worker端和监管端的所有Activity和Fragment布局应用深灰色(#212121)背景白字深色主题。

## 2. 实施
- [ ] 2.1 [`app/src/main/res/values/colors.xml`](app/src/main/res/values/colors.xml)，添加深灰色颜色定义 `<color name="background_dark">#FF212121</color>`。
- [ ] 2.2 [`app/src/main/res/layout/activity_login_worker.xml`](app/src/main/res/layout/activity_login_worker.xml)，移除 `android:background="@android:color/white"` 属性。
- [ ] 2.3 [`app/src/main/res/layout/activity_login_worker.xml`](app/src/main/res/layout/activity_login_worker.xml)，移除 `android:backgroundTint="@color/white"` 属性。
- [ ] 2.4 [`app/src/main/res/layout/activity_login_worker.xml`](app/src/main/res/layout/activity_login_worker.xml)，将 `android:textColor="@android:color/black"` 改为 `android:textColor="@color/white"`。
- [ ] 2.5 [`app/src/main/res/layout/activity_login_regulator.xml`](app/src/main/res/layout/activity_login_regulator.xml)，移除 `android:background="@android:color/white"` 属性。
- [ ] 2.6 [`app/src/main/res/layout/activity_main_worker.xml`](app/src/main/res/layout/activity_main_worker.xml)，将 `app:backgroundTint="@android:color/white"` 改为 `app:backgroundTint="@color/background_dark"`。
- [ ] 2.7 [`app/src/main/res/layout/fragment_detection.xml`](app/src/main/res/layout/fragment_detection.xml)，移除 `android:background="@color/white"` 属性。
- [ ] 2.8 [`app/src/main/res/layout/fragment_monitoring.xml`](app/src/main/res/layout/fragment_monitoring.xml)，将 `android:background="#F5F5F5"` 改为 `android:background="@color/background_dark"`。
- [ ] 2.9 [`app/src/main/res/layout/fragment_monitoring.xml`](app/src/main/res/layout/fragment_monitoring.xml)，将 `android:background="@color/white"` 改为 `android:background="@color/light_black"`。
- [ ] 2.10 [`app/src/main/res/layout/fragment_monitoring.xml`](app/src/main/res/layout/fragment_monitoring.xml)，将 `android:textColor="#999999"` 改为 `android:textColor="@color/text_tertiary"`。
- [ ] 2.11 [`app/src/main/res/layout/fragment_monitoring_worker.xml`](app/src/main/res/layout/fragment_monitoring_worker.xml)，移除 `android:background="@android:color/white"` 属性。
- [ ] 2.12 [`app/src/main/res/layout/fragment_monitoring_worker.xml`](app/src/main/res/layout/fragment_monitoring_worker.xml)，将 `android:background="#E0E0E0"` 改为 `android:background="@color/light_black"`。
- [ ] 2.13 [`app/src/main/res/layout/fragment_profile.xml`](app/src/main/res/layout/fragment_profile.xml)，将 `android:background="#F5F5F5"` 改为 `android:background="@color/background_dark"`。
- [ ] 2.14 [`app/src/main/res/layout/fragment_profile.xml`](app/src/main/res/layout/fragment_profile.xml)，将 `app:cardBackgroundColor="@color/white"` 改为 `app:cardBackgroundColor="@color/light_black"`。
- [ ] 2.15 [`app/src/main/res/layout/fragment_profile.xml`](app/src/main/res/layout/fragment_profile.xml)，将 `app:tint="@color/brand_primary"` 改为 `app:tint="@color/white"`。
- [ ] 2.16 [`app/src/main/res/layout/fragment_profile_worker.xml`](app/src/main/res/layout/fragment_profile_worker.xml)，移除 `android:background="@color/white"` 属性。
- [ ] 2.17 [`app/src/main/res/layout/fragment_work_record.xml`](app/src/main/res/layout/fragment_work_record.xml)，将 `android:background="#F5F5F5"` 改为 `android:background="@color/background_dark"`。
- [ ] 2.18 [`app/src/main/res/layout/fragment_work_record.xml`](app/src/main/res/layout/fragment_work_record.xml)，将 `android:background="@color/white"` 改为 `android:background="@color/light_black"`。
- [ ] 2.19 [`app/src/main/res/layout/fragment_work_record.xml`](app/src/main/res/layout/fragment_work_record.xml)，将 `app:titleTextColor="#000000"` 改为 `app:titleTextColor="@color/white"`。
- [ ] 2.20 [`app/src/main/res/drawable/device_list_border.xml`](app/src/main/res/drawable/device_list_border.xml)，将 `android:color="@android:color/darker_gray"` 改为 `android:color="@color/text_tertiary"`。
- [ ] 2.21 [`app/src/main/res/drawable/status_box_border.xml`](app/src/main/res/drawable/status_box_border.xml)，将 `android:color="@android:color/black"` 改为 `android:color="@color/text_tertiary"`。