import splusjava.RpcException;
import splusjava.Session;
import splusjava.SoroushClient;
import splusjava.mtproto.MTProtoSender;
import splusjava.tl.TLObject;

/**
 * Minimal end-to-end example: connects (creating a session file on first run), logs in (handles
 * two-step verification too), resolves a username and sends it a text message. Compile against
 * dist/splusjava.jar:
 *
 *   javac -cp dist/splusjava.jar examples/SendMessageExample.java -d /tmp/out
 *   java -cp dist/splusjava.jar:/tmp/out SendMessageExample
 */
public final class SendMessageExample {
    public static void main(String[] args) throws Exception {
        java.io.File sessionFile = new java.io.File("example.session");
        String saved = sessionFile.exists() ? new String(java.nio.file.Files.readAllBytes(sessionFile.toPath()), "UTF-8") : "";
        Session session = Session.parse(saved);

        SoroushClient client = new SoroushClient(session);
        client.setUpdateListener(new MTProtoSender.UpdateListener() {
            public void onUpdate(TLObject update) {
                System.out.println("update: " + update.getName());
            }
        });
        client.connect(); // auto-reconnects on drop by default; call client.setAutoReconnect(false) to disable
        java.nio.file.Files.write(sessionFile.toPath(), session.save().getBytes("UTF-8"));

        java.io.BufferedReader stdin = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));

        // First run: log in with a phone number (skip this block once the saved session already
        // carries an authorized key, i.e. on every run after the first successful login).
        System.out.print("Phone number (e.g. +989121234567), or blank if already logged in: ");
        String phone = stdin.readLine();
        if (phone != null && phone.trim().length() > 0) {
            phone = phone.trim();
            String phoneCodeHash = client.sendCode(phone);

            System.out.print("Code you received: ");
            String code = stdin.readLine().trim();
            try {
                client.signIn(phone, phoneCodeHash, code);
            } catch (RpcException e) {
                if (e.is("SESSION_PASSWORD_NEEDED")) {
                    System.out.print("Two-step verification password: ");
                    String password = stdin.readLine();
                    client.checkPassword(password);
                } else {
                    throw e;
                }
            }
            java.nio.file.Files.write(sessionFile.toPath(), session.save().getBytes("UTF-8"));
            System.out.println("Logged in and session saved to " + sessionFile.getAbsolutePath());
        }

        TLObject peer = client.resolvePeer("SoroushSupport");
        TLObject sent = client.sendMessage(peer, "Hello from splusjava!");
        System.out.println("Sent: " + sent.toJson());

        client.disconnect();
    }
}
