package heger.christian.checkbook.accounts;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * @author Android Developer
 */
public class AuthenticatorService extends Service {

	@Override
	public IBinder onBind(Intent intent) {
		Authenticator authenticator = new heger.christian.checkbook.accounts.Authenticator(this);
		return authenticator.getIBinder();
	}

}
