package android.ext.settings;

/** @hide */
public class ExtSettings {

    public static final BoolSetting AUTO_GRANT_OTHER_SENSORS_PERMISSION = new BoolSetting(
            Setting.Scope.PER_USER, "auto_grant_OTHER_SENSORS_perm", true);

    public static final BoolSetting SCREENSHOT_TIMESTAMP_EXIF = new BoolSetting(
            Setting.Scope.PER_USER, "screenshot_timestamp_exif", false);

    private ExtSettings() {}
}
