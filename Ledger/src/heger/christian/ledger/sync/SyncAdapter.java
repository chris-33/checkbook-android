package heger.christian.ledger.sync;

import heger.christian.ledger.accounts.Authenticator;
import heger.christian.ledger.db.LedgerDbHelper.SequenceAnchorContract;
import heger.christian.ledger.network.Endpoints;
import heger.christian.ledger.network.LedgerSSLContextFactory;
import heger.christian.ledger.network.TruststoreException;
import heger.christian.ledger.providers.Journaler;
import heger.christian.ledger.providers.LedgerContentProvider;
import heger.christian.ledger.providers.MetaContentProvider.JournalContract;
import heger.christian.ledger.providers.MetaContentProvider.RevisionTableContract;
import heger.christian.ledger.providers.SharedTransaction;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONException;
import org.json.JSONObject;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.annotation.TargetApi;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.goebl.david.Response;
import com.goebl.david.Webb;
import com.goebl.david.WebbException;


public class SyncAdapter extends AbstractThreadedSyncAdapter {
	private static final String TAG = SyncAdapter.class.getSimpleName();

	/**
	 * Set up the sync adapter
	 */
	public SyncAdapter(Context context, boolean autoInitialize) {
		super(context, autoInitialize);
		/*
		 * If your app uses a content resolver, get an instance of it
		 * from the incoming Context
		 */
	}

	/**
	 * Set up the sync adapter. This form of the
	 * constructor maintains compatibility with Android 3.0
	 * and later platform versions
	 */
	public SyncAdapter(
			Context context,
			boolean autoInitialize,
			boolean allowParallelSyncs) {
		super(context, autoInitialize, allowParallelSyncs);
	}

	private List<ContentProviderOperation> getUpdateRevisionsOperation(JournalSnapshot journal, RevisionTableSnapshot revisions) {
		List<ContentProviderOperation> operations = new LinkedList<ContentProviderOperation>();
		// Handle revision updates for creations and deletions
//		Map<String, Set<Long>> agenda = journal.getAgenda(Journaler.OP_TYPE_CREATE);
//		agenda.putAll(journal.getAgenda(Journaler.OP_TYPE_DELETE));
		@SuppressWarnings("unchecked")
		Map<String, Set<Long>>[] agenda = new Map[] { journal.getAgenda(Journaler.OP_TYPE_CREATE), journal.getAgenda(Journaler.OP_TYPE_UPDATE), journal.getAgenda(Journaler.OP_TYPE_DELETE) };
		byte CREATIONS = 0;
		byte UPDATES = 1;
		byte DELETIONS = 2;
		for (byte i = CREATIONS; i <= DELETIONS; i++)
			for (String table: agenda[i].keySet()) {
				for (long row: agenda[i].get(table)) {
					Set<String> columns = i == UPDATES ? journal.getColumns(table, row) : revisions.getRevisionsForRow(table, row).keySet();
					for (String column: columns)
						operations.add(ContentProviderOperation.newUpdate(RevisionTableContract.CONTENT_URI)
								.withSelection(
										RevisionTableContract.COL_NAME_TABLE + "=? "
												+ "and " + RevisionTableContract.COL_NAME_ROW + "=" + row + " "
												+ "and " + RevisionTableContract.COL_NAME_COLUMN + "=?",
												new String[] { table, column })
												.withValue(RevisionTableContract.COL_NAME_REVISION, revisions.getRevision(table, row, column) + 1)
												.build());
				}
			}
		return operations;
	}

	@Override
	@TargetApi(android.os.Build.VERSION_CODES.JELLY_BEAN_MR1)
	public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
		ContentResolver resolver = getContext().getContentResolver();

		/*
		 * Get an access token
		 */
		String token = null;
		try {
			token = AccountManager.get(getContext()).blockingGetAuthToken(account, Authenticator.TOKEN_TYPE_ACCESS, true);
		} catch (OperationCanceledException x) {
			return;
		} catch (AuthenticatorException x) {
			syncResult.stats.numAuthExceptions++;
			return;
		} catch (IOException x) {
			syncResult.stats.numIoExceptions++;
			return;
		}

		/*
		 * Client side preparation phase
		 */
		// Find out from which sequence anchor we need to sync
		Cursor cursor = resolver.query(SequenceAnchorContract.CONTENT_URI, null, null, null, null);
		long anchor = (cursor.moveToFirst()) ? cursor.getLong(0) : 0;

		// Create journal and revision table snapshots
		JournalSnapshot journalSnapshot = JournalSnapshot.createFromCursor(resolver.query(JournalContract.CONTENT_URI, null, "sqn >= " + anchor, null, JournalContract.COL_NAME_TABLE));
		RevisionTableSnapshot revisions = RevisionTableSnapshot.createFromCursor(resolver.query(RevisionTableContract.CONTENT_URI, null, null, null, null));

		Marshaller marshaller = new Marshaller();
		JSONObject json = marshaller.marshal(journalSnapshot, revisions, provider, anchor);


		Webb webb = com.goebl.david.Webb.create();
		try {
			webb.setSSLSocketFactory(new LedgerSSLContextFactory(getContext()).createSSLContext().getSocketFactory());
		} catch (TruststoreException x) {
			Log.e(TAG, "Could not load the truststore for syncing. \n"
					+ "Error type: " + x.getCause().getClass().getSimpleName() + "\n"
					+ "Error message: " + x.getCause().getMessage());
			syncResult.stats.numIoExceptions++;
			return;
		}

		Response<JSONObject> response = null;
		try {
			response = webb.post(Endpoints.URL_SYNC)
					.header(Webb.HDR_ACCEPT, Webb.APP_JSON)
					.header(Webb.HDR_AUTHORIZATION, "Bearer " + token)
					.header(Webb.HDR_CONTENT_TYPE, Webb.APP_JSON)
					.body(json)
					.ensureSuccess()
					.asJsonObject();
		} catch (WebbException x) {
			if (x.getResponse() != null && x.getResponse().getStatusCode() == HttpsURLConnection.HTTP_FORBIDDEN) {
				syncResult.stats.numAuthExceptions++;
				return;
			} else {
				syncResult.stats.numIoExceptions++;
				return;
			}
		}

		/*
		 * Client side processing phase: process the received message from the server
		 */
		json = response.getBody();
		Unmarshaller unmarshaller = new Unmarshaller();
		List<ContentProviderOperation> operations = getUpdateRevisionsOperation(journalSnapshot, revisions);
		operations.addAll(unmarshaller.unmarshal(json));
		try {
			operations.add(ContentProviderOperation.newInsert(SequenceAnchorContract.CONTENT_URI)
					.withValue(heger.christian.ledger.providers.MetaContentProvider.SequenceAnchorContract.COL_NAME_SEQUENCE_ANCHOR, json.get(Unmarshaller.JSON_FIELD_ANCHOR))
					.build());
		} catch (JSONException x) {
			syncResult.stats.numParseExceptions++;
		}

		try {
			// Disable journaling
			boolean goThroughResolver = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
			if (!goThroughResolver) {
				// Use ContentProviderClient if platform is >= API 17 (ContentProviderClient.call unavailable on earlier versions)
				try {
					provider.call(LedgerContentProvider.METHOD_DISABLE_JOURNALING, null, null);
				} catch (RemoteException x) {
					// Remote call on ContentProviderClient failed, go through content resolver instead
					goThroughResolver = true;
				}
			}
			if (goThroughResolver) {
				// Use content resolver if platform is < API 17 or the client failed
				getContext().getContentResolver().call(LedgerContentProvider.CONTENT_URI, LedgerContentProvider.METHOD_DISABLE_JOURNALING, null, null);
			}

			SharedTransaction transaction = SharedTransaction.newInstance(getContext());
			transaction.applyBatch(operations);
		} catch (OperationApplicationException x) {
			syncResult.databaseError = true;
		} finally {
			// Enable journaling
			boolean goThroughResolver = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
			if (!goThroughResolver) {
				try {
					provider.call(LedgerContentProvider.METHOD_ENABLE_JOURNALING, null, null);
				} catch (RemoteException x) {
					// Going through the client failed, go through the content resolver
					goThroughResolver = true;
				}
			}
			if (goThroughResolver) {
				getContext().getContentResolver().call(LedgerContentProvider.CONTENT_URI, LedgerContentProvider.METHOD_ENABLE_JOURNALING, null, null);
			}
		}
	}

}
