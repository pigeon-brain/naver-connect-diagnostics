package local.son.connectdiagnostics;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONException;
import org.json.JSONObject;

public final class CardPaymentDiagnostics {
    private static final String PAYMENT_PACKAGE = "com.naverfin.locus";
    private static final String PAYMENT_SERVICE = PAYMENT_PACKAGE + ".LocusForegroundService";
    private static final String SERIAL_DEVICE = "/dev/ttyS1";

    private CardPaymentDiagnostics() {
    }

    public static JSONObject inspect(Context context) {
        PackageManager packages = context.getPackageManager();
        JSONObject report = new JSONObject();
        try {
            report.put("payment_app", inspectPackage(packages));
            report.put("payment_service", inspectService(packages));
            report.put("serial_device", inspectSerialNode());
            report.put("payment_module", "펌웨어 응답 미확인. 외부 앱용 상태 조회 API 확인되지 않음.");
            report.put("ic", "삽입 감지 미확인. 제조사 리더 인터페이스 필요.");
            report.put("msr", "스와이프 감지 미확인. 카드 데이터 없는 공개 감지 API 확인되지 않음.");
            report.put("payment_nfc", "결제용 RF 리더 미확인. 제조사 리더 인터페이스 필요.");
        } catch (JSONException error) {
            throw new IllegalStateException("결제 하드웨어 검사 결과를 만들 수 없습니다.", error);
        }
        return report;
    }

    private static String inspectPackage(PackageManager packages) {
        try {
            PackageInfo info = packages.getPackageInfo(PAYMENT_PACKAGE, 0);
            String state = info.applicationInfo != null && !info.applicationInfo.enabled
                    ? "비활성" : "설치 확인";
            return info.versionName == null ? state : state + " (v" + info.versionName + ")";
        } catch (PackageManager.NameNotFoundException | SecurityException error) {
            return "패키지 조회 안 됨";
        }
    }

    private static String inspectService(PackageManager packages) {
        try {
            ComponentName component = new ComponentName(PAYMENT_PACKAGE, PAYMENT_SERVICE);
            ServiceInfo info = packages.getServiceInfo(component,
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            String state = info.enabled && info.applicationInfo.enabled ? "등록됨" : "비활성";
            String exposure = info.exported ? "외부 공개" : "외부 비공개";
            return state + " · " + exposure + " · 실행 여부 미확인";
        } catch (PackageManager.NameNotFoundException | SecurityException error) {
            return "서비스 조회 안 됨";
        }
    }

    private static boolean canReadSerialNode() throws ErrnoException {
        try {
            return Os.access(SERIAL_DEVICE, OsConstants.R_OK);
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM) {
                return false;
            }
            throw error;
        }
    }

    private static String inspectSerialNode() {
        try {
            if (!OsConstants.S_ISCHR(Os.stat(SERIAL_DEVICE).st_mode)) {
                return "경로 확인 · 문자 장치 아님";
            }
            if (canReadSerialNode()) {
                return "장치 확인 · 읽기 권한 확인 · 통신 미검사";
            }
            return "장치 확인 · 읽기 권한 없음";
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.ENOENT) {
                return "장치 경로 없음";
            }
            if (error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM) {
                return "장치 접근 권한 없음";
            }
            return "장치 조회 실패 (errno " + error.errno + ")";
        } catch (SecurityException error) {
            return "장치 접근 권한 없음";
        }
    }
}
