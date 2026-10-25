import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Root helper, run with app_process (no Android jar needed: everything by reflection).
 * subid SLOT | getdata | setdata SUBID | getnt SUBID | setnt SUBID MASK
 * activesub | getautosw SUBID | setautosw SUBID 0|1   (Android's hidden "switch mobile data automatically")
 */
public class NetHelper {
    /** TelephonyManager.MOBILE_DATA_POLICY_AUTO_DATA_SWITCH (Android 13+) */
    static final int AUTO_DATA_SWITCH = 3;

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

    /** Calls an ITelephony "network types" method whatever its exact signature is on this Android version:
     *  ints are filled subId, then reason (0 = user); a long is the mask; a String is the calling package. */
    static Object callTypes(Object o, String name, int sub, long mask) throws Exception {
        StringBuilder cands = new StringBuilder();
        Exception last = null;
        for (Method m : o.getClass().getMethods()) {
            String n = m.getName();
            if (n.toLowerCase().contains("networktype")) {
                cands.append("\n  ").append(n).append('(');
                for (Class<?> c : m.getParameterTypes()) cands.append(c.getSimpleName()).append(' ');
                cands.append(')');
            }
            if (!n.equals(name)) continue;
            Class<?>[] pt = m.getParameterTypes();
            Object[] args = new Object[pt.length];
            int ints = 0;
            boolean ok = true;
            for (int i = 0; i < pt.length; i++) {
                if (pt[i] == int.class) args[i] = (ints++ == 0) ? sub : 0;
                else if (pt[i] == long.class) args[i] = mask;
                else if (pt[i] == String.class) args[i] = "com.android.shell";
                else { ok = false; break; }
            }
            if (!ok) continue;
            try {
                return m.invoke(o, args);
            } catch (InvocationTargetException e) {
                Throwable c = e.getCause();
                last = c instanceof Exception ? (Exception) c : e;
            } catch (IllegalArgumentException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new Exception("no usable " + name + ". Network-type methods on this system:" + cands);
    }

    public static void main(String[] a) {
        try {
            String cmd = a.length > 0 ? a[0] : "";
            Object isub = null, tel = null;
            if (cmd.equals("subid") || cmd.equals("activesub") || cmd.endsWith("data")) isub = stub("isub", "com.android.internal.telephony.ISub");
            else tel = stub("phone", "com.android.internal.telephony.ITelephony");
            switch (cmd) {
                case "subid": {
                    Object r = call(isub, "getSubId", Integer.parseInt(a[1]));
                    // Older Android returns int[], newer returns a plain int.
                    if (r instanceof int[]) {
                        int[] ids = (int[]) r;
                        System.out.println(ids.length > 0 ? ids[0] : -1);
                    } else {
                        System.out.println(r);
                    }
                    break;
                }
                case "getdata":
                    System.out.println(call(isub, "getDefaultDataSubId"));
                    break;
                case "setdata":
                    call(isub, "setDefaultDataSubId", Integer.parseInt(a[1]));
                    System.out.println("ok");
                    break;
                case "activesub":
                    // the SIM that really carries data now (differs from the default one while
                    // Android's auto data switch has moved data to the other SIM)
                    System.out.println(call(isub, "getActiveDataSubscriptionId"));
                    break;
                case "getautosw":
                    System.out.println(call(tel, "isMobileDataPolicyEnabled", Integer.parseInt(a[1]), AUTO_DATA_SWITCH));
                    break;
                case "setautosw":
                    call(tel, "setMobileDataPolicyEnabled", Integer.parseInt(a[1]), AUTO_DATA_SWITCH, a[2].equals("1"));
                    System.out.println("ok");
                    break;
                case "getnt":
                    System.out.println(call(tel, "getAllowedNetworkTypesForReason", Integer.parseInt(a[1]), 0));
                    break;
                case "setnt":
                    System.out.println(callTypes(tel, "setAllowedNetworkTypesForReason", Integer.parseInt(a[1]), Long.parseLong(a[2])));
                    break;
                default:
                    System.err.println("usage: subid SLOT | getdata | setdata SUBID | activesub | getnt SUBID | setnt SUBID MASK | getautosw SUBID | setautosw SUBID 0|1");
                    System.exit(2);
            }
        } catch (Throwable t) {
            System.err.println("NetHelper " + (a.length > 0 ? a[0] : "") + ": " + t);
            System.exit(1);
        }
    }
}
