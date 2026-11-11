package ftpclient;

/**
 * Điểm vào cho gói jpackage: tránh lỗi "JavaFX runtime components are missing" khi chạy từ classpath.
 * Lớp này không kế thừa {@code Application} nên JVM không đòi module JavaFX lúc khởi động; nó chỉ chuyển
 * sang {@link Main#main(String[])}.
 */
public class Launcher {
    public static void main(String[] args) {
        Main.main(args);
    }
}
