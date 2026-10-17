import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Root helper, run with app_process (no Android jar needed: everything by reflection).
 * subid SLOT | getdata | setdata SUBID | getnt SUBID | setnt SUBID MASK
 */
public class NetHelper {
    static Object stub(String svc, String iface) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Object b = sm.getMethod("getService", String.class).invoke(null, svc);
        if (b == null) throw new Exception("no service " + svc);
        return Class.forName(iface + "$Stub").getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, b);
    }

    static Object call(Object o, String name, Object... a) throws Exception {
        Exception last = new Exception("no method " + name + "/" + a.length);
        for (Method m : o.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != a.length) continue;
            try {
                return m.invoke(o, a);
            } catch (IllegalArgumentException e) {
                last = e;
            } catch (InvocationTargetException e) {
                Throwable c = e.getCause();
                throw c instanceof Exception ? (Exception) c : e;
            }
        }
        throw last;
    }

    public static void main(String[] a) {
        try {
            String cmd = a.length > 0 ? a[0] : "";
            Object isub = null, tel = null;
            if (cmd.equals("subid") || cmd.endsWith("data")) isub = stub("isub", "com.android.internal.telephony.ISub");
            else tel = stub("phone", "com.android.internal.telephony.ITelephony");
            switch (cmd) {
                case "subid": {
                    Object r = call(isub, "getSubId", Integer.parseInt(a[1]));
                    System.out.println(((int[]) r)[0]);
                    break;
                }
                case "getdata":
                    System.out.println(call(isub, "getDefaultDataSubId"));
                    break;
                case "setdata":
                    call(isub, "setDefaultDataSubId", Integer.parseInt(a[1]));
                    System.out.println("ok");
                    break;
                case "getnt":
                    System.out.println(call(tel, "getAllowedNetworkTypesForReason", Integer.parseInt(a[1]), 0));
                    break;
                case "setnt":
                    System.out.println(call(tel, "setAllowedNetworkTypesForReason", Integer.parseInt(a[1]), 0, Long.parseLong(a[2])));
                    break;
                default:
                    System.err.println("usage: subid SLOT | getdata | setdata SUBID | getnt SUBID | setnt SUBID MASK");
                    System.exit(2);
            }
        } catch (Throwable t) {
            System.err.println("NetHelper " + (a.length > 0 ? a[0] : "") + ": " + t);
            System.exit(1);
        }
    }
}
