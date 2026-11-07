/*
 * This file is a part of Vibegram X, a fork of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.webapp;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.drinkless.tdlib.TdApi;
import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.AppContext;
import org.thunderdog.challegram.widget.PopupLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Device storage and secure storage of a mini app, per (account user, bot).
 *
 * <p>Files live in {@code <filesDir>/apps_storage/} with the names of the official client:
 * {@code <userId>_<botId>} (plain JSON) and {@code <storageId>_<botId>_s} (AES-GCM encrypted JSON),
 * where {@code secure_config.json} maps every storage id to its owner. Storages of accounts that
 * logged out are kept so that {@link #restore} can import them. All file work runs on the
 * {@link Background} thread, which serializes operations of every instance; the public methods are
 * called on the UI thread.
 */
public final class WebAppStorage {
  public static final String
    ERROR_QUOTA_EXCEEDED = "QUOTA_EXCEEDED",
    ERROR_UNKNOWN = "UNKNOWN_ERROR",
    ERROR_UNSUPPORTED = "UNSUPPORTED",
    ERROR_KEY_INVALID = "KEY_INVALID",
    ERROR_VALUE_INVALID = "VALUE_INVALID",
    ERROR_RESTORE_UNAVAILABLE = "RESTORE_UNAVAILABLE",
    ERROR_RESTORE_CANCELLED = "RESTORE_CANCELLED",
    ERROR_STORAGE_NOT_EMPTY = "STORAGE_NOT_EMPTY";

  /** Always called on the UI thread; {@code error == null} means success. */
  public interface Callback {
    void onResult (@Nullable String value, @Nullable String error, boolean canRestore);
  }

  private static final String DIRECTORY_NAME = "apps_storage";
  private static final String SECURE_CONFIG_NAME = "secure_config.json";
  private static final String SECURE_FILE_SUFFIX = "_s";
  private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
  private static final String KEY_ALIAS = "MiniAppsKey";
  private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final int MAX_SIZE = 5 * 1024 * 1024;
  // Room for the IV and the GCM tag of an encrypted file at the size limit.
  private static final int MAX_SECURE_FILE_OVERHEAD = 1024;
  private static final int MAX_SECURE_KEY_COUNT = 10;
  private static final Charset UTF_8 = Charset.forName("UTF-8");

  private static final String
    CONFIG_USER_ID = "user_id",
    CONFIG_USER_NAME = "user_name",
    CONFIG_CREATED_AT = "created_at",
    CONFIG_EDITED_AT = "edited_at";

  private final long accountUserId;
  private final String accountUserName;
  private final long botUserId;
  private final boolean secure;

  // Accessed only on the Background thread.
  private @Nullable JSONObject data;
  private @Nullable String storageId;

  public WebAppStorage (Tdlib tdlib, long botUserId, boolean secure) {
    this.accountUserId = tdlib.myUserId(true);
    TdApi.User user = tdlib.myUser();
    this.accountUserName = user != null ? TD.getUserName(user) : "";
    this.botUserId = botUserId;
    this.secure = secure;
  }

  /** @param value null removes the key */
  public void save (String key, @Nullable String value, Callback callback) {
    Background.instance().post(() -> {
      String error = null;
      try {
        saveImpl(key, value);
      } catch (StorageException e) {
        error = e.error;
      } catch (OutOfMemoryError e) {
        error = ERROR_QUOTA_EXCEEDED;
      }
      deliver(callback, null, error, false);
    });
  }

  /**
   * The value is null when the key is missing; then {@code canRestore} tells whether
   * {@link #restore} can help.
   */
  public void get (String key, Callback callback) {
    final long[] activeUserIds = secure ? collectActiveUserIds() : null;
    Background.instance().post(() -> {
      String value = null, error = null;
      boolean canRestore = false;
      try {
        checkSupported();
        checkKey(key);
        JSONObject data = loadData();
        value = valueOf(data.opt(key));
        // Restoring is possible only into an empty storage, see restoreImpl.
        if (value == null && activeUserIds != null && data.length() == 0) {
          canRestore = !findRestoreCandidates(key, activeUserIds).isEmpty();
        }
      } catch (StorageException e) {
        error = e.error;
      } catch (OutOfMemoryError e) {
        error = ERROR_QUOTA_EXCEEDED;
      }
      deliver(callback, value, error, canRestore);
    });
  }

  public void clear (Callback callback) {
    Background.instance().post(() -> {
      String error = null;
      try {
        clearImpl();
      } catch (StorageException e) {
        error = e.error;
      } catch (OutOfMemoryError e) {
        error = ERROR_QUOTA_EXCEEDED;
      }
      deliver(callback, null, error, false);
    });
  }

  /**
   * Secure storage only: offers the storages of accounts that are no longer logged in on this
   * device and have {@code key}, moves the chosen one to the current account and returns the value
   * of {@code key}.
   */
  public void restore (ViewController<?> context, String botName, String key, Callback callback) {
    final long[] activeUserIds = collectActiveUserIds();
    Background.instance().post(() -> {
      List<RestoreCandidate> candidates;
      try {
        if (!secure) {
          throw new StorageException(ERROR_UNSUPPORTED);
        }
        checkSupported();
        checkKey(key);
        if (loadData().length() > 0) {
          throw new StorageException(ERROR_STORAGE_NOT_EMPTY);
        }
        candidates = findRestoreCandidates(key, activeUserIds);
        if (candidates.isEmpty()) {
          throw new StorageException(ERROR_RESTORE_UNAVAILABLE);
        }
      } catch (StorageException e) {
        deliver(callback, null, e.error, false);
        return;
      } catch (OutOfMemoryError e) {
        deliver(callback, null, ERROR_QUOTA_EXCEEDED, false);
        return;
      }
      UI.post(() -> showRestoreChooser(context, botName, key, candidates, callback));
    });
  }

  // UI

  private void showRestoreChooser (
    ViewController<?> context,
    String botName,
    String key,
    List<RestoreCandidate> candidates,
    Callback callback
  ) {
    if (context.isDestroyed()) {
      callback.onResult(null, ERROR_RESTORE_CANCELLED, false);
      return;
    }
    CharSequence text = Lang.getStringBold(R.string.vg_BotRestoreStorageText, botName);
    if (candidates.size() == 1) {
      RestoreCandidate candidate = candidates.get(0);
      AlertPopup alert = context.showAlert(new AlertPopup.Builder(context.context())
        .setTitle(Lang.getString(R.string.vg_BotRestoreStorageTitle))
        .setMessage(TextUtils.concat(text, "\n\n", candidate.describe()))
        .setPositiveButton(Lang.getString(R.string.vg_BotRestoreStorageButton), (dialog, which) ->
          restoreFrom(candidate, key, callback)
        )
        .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) ->
          callback.onResult(null, ERROR_RESTORE_CANCELLED, false)
        )
        .setOnCancelListener(dialog -> callback.onResult(null, ERROR_RESTORE_CANCELLED, false))
      );
      if (alert == null) {
        callback.onResult(null, ERROR_RESTORE_CANCELLED, false);
      }
      return;
    }
    int count = candidates.size();
    int[] ids = new int[count + 1];
    String[] titles = new String[count + 1];
    for (int i = 0; i < count; i++) {
      // Indexes + 1 can't collide with resource ids such as btn_cancel.
      ids[i] = i + 1;
      titles[i] = candidates.get(i).describe();
    }
    ids[count] = R.id.btn_cancel;
    titles[count] = Lang.getString(R.string.Cancel);
    CharSequence info = TextUtils.concat(
      Lang.getString(R.string.vg_BotRestoreStorageTitle), "\n\n",
      text, "\n\n",
      Lang.getString(R.string.vg_BotRestoreStorageHeader)
    );
    PopupLayout popup = context.showOptions(info, ids, titles, null, null, (itemView, id) -> {
      int index = id - 1;
      if (id != R.id.btn_cancel && index >= 0 && index < count) {
        restoreFrom(candidates.get(index), key, callback);
      } else {
        callback.onResult(null, ERROR_RESTORE_CANCELLED, false);
      }
      return true;
    });
    if (popup == null) {
      callback.onResult(null, ERROR_RESTORE_CANCELLED, false);
    }
  }

  private void restoreFrom (RestoreCandidate candidate, String key, Callback callback) {
    Background.instance().post(() -> {
      String value = null, error = null;
      try {
        value = restoreImpl(candidate, key);
      } catch (StorageException e) {
        error = e.error;
      } catch (OutOfMemoryError e) {
        error = ERROR_QUOTA_EXCEEDED;
      }
      deliver(callback, value, error, false);
    });
  }

  private static void deliver (
    Callback callback,
    @Nullable String value,
    @Nullable String error,
    boolean canRestore
  ) {
    UI.post(() -> callback.onResult(value, error, canRestore));
  }

  // Operations (Background thread)

  private void saveImpl (String key, @Nullable String value) throws StorageException {
    checkSupported();
    checkKey(key);
    JSONObject data = loadData();
    Object previousValue = data.opt(key);
    if (value == null) {
      if (previousValue == null) {
        return;
      }
      data.remove(key);
    } else {
      if ((long) key.length() + value.length() > MAX_SIZE) {
        throw new StorageException(ERROR_QUOTA_EXCEEDED);
      }
      if (secure && previousValue == null && data.length() >= MAX_SECURE_KEY_COUNT) {
        throw new StorageException(ERROR_QUOTA_EXCEEDED);
      }
      putValue(data, key, value);
    }
    try {
      writeData(data);
    } catch (StorageException e) {
      // Keep the cached object equal to the file.
      if (previousValue != null) {
        putValue(data, key, previousValue);
      } else {
        data.remove(key);
      }
      throw e;
    }
  }

  private void clearImpl () throws StorageException {
    checkSupported();
    JSONObject empty = new JSONObject();
    if (!secure || findStorageId(false) != null) {
      writeData(empty);
    }
    data = empty;
  }

  private String restoreImpl (RestoreCandidate candidate, String key) throws StorageException {
    checkSupported();
    if (loadData().length() > 0) {
      throw new StorageException(ERROR_STORAGE_NOT_EMPTY);
    }
    JSONObject config = readConfig();
    JSONObject entry = config.optJSONObject(candidate.storageId);
    File restoredFile = getSecureFile(candidate.storageId);
    if (entry == null || !restoredFile.exists()) {
      throw new StorageException(ERROR_RESTORE_UNAVAILABLE);
    }
    try {
      entry.put(CONFIG_USER_ID, accountUserId);
      entry.put(CONFIG_USER_NAME, accountUserName);
      entry.put(CONFIG_EDITED_AT, System.currentTimeMillis());
    } catch (JSONException e) {
      throw new StorageException(ERROR_UNKNOWN);
    }
    writeConfig(config);
    String currentStorageId = findStorageId(false);
    if (currentStorageId != null && !currentStorageId.equals(candidate.storageId)) {
      // The current storage of this bot is empty: without its file the restored one is found from
      // now on.
      deleteFile(getSecureFile(currentStorageId));
    }
    storageId = candidate.storageId;
    data = null;
    return valueOf(loadData().opt(key));
  }

  private void checkSupported () throws StorageException {
    if (secure && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      throw new StorageException(ERROR_UNSUPPORTED);
    }
    if (accountUserId == 0) {
      throw new StorageException(ERROR_UNKNOWN);
    }
  }

  private static void checkKey (@Nullable String key) throws StorageException {
    if (key == null || key.isEmpty()) {
      throw new StorageException(ERROR_KEY_INVALID);
    }
  }

  private static @Nullable String valueOf (@Nullable Object value) {
    if (value == null || value == JSONObject.NULL) {
      return null;
    }
    return value instanceof String ? (String) value : value.toString();
  }

  private static void putValue (JSONObject data, String key, Object value) throws StorageException {
    try {
      data.put(key, value);
    } catch (JSONException e) {
      throw new StorageException(ERROR_VALUE_INVALID);
    }
  }

  private JSONObject loadData () throws StorageException {
    if (data != null) {
      return data;
    }
    JSONObject result;
    if (secure) {
      String id = findStorageId(false);
      File file = id != null ? getSecureFile(id) : null;
      if (file == null || !file.exists()) {
        result = new JSONObject();
      } else {
        try {
          result = readJsonFile(file, true);
        } catch (GeneralSecurityException | IOException | JSONException e) {
          Log.e("Cannot read secure storage of a mini app", e);
          // As the official client: a storage that can't be decrypted is reset.
          JSONObject empty = new JSONObject();
          data = empty;
          writeData(empty);
          throw new StorageException(ERROR_UNKNOWN);
        }
      }
    } else {
      File file = getDeviceFile();
      if (!file.exists()) {
        result = new JSONObject();
      } else {
        try {
          result = readJsonFile(file, false);
        } catch (GeneralSecurityException | IOException | JSONException e) {
          Log.e("Cannot read device storage of a mini app", e);
          result = new JSONObject();
        }
      }
    }
    data = result;
    return result;
  }

  private void writeData (JSONObject data) throws StorageException {
    byte[] bytes = data.toString().getBytes(UTF_8);
    if (bytes.length > MAX_SIZE) {
      throw new StorageException(ERROR_QUOTA_EXCEEDED);
    }
    File file;
    if (secure) {
      String id = findStorageId(true);
      if (id == null) {
        throw new StorageException(ERROR_UNKNOWN);
      }
      file = getSecureFile(id);
      try {
        bytes = encrypt(bytes);
      } catch (GeneralSecurityException | IOException e) {
        Log.e("Cannot encrypt secure storage of a mini app", e);
        throw new StorageException(ERROR_UNKNOWN);
      }
    } else {
      file = getDeviceFile();
    }
    try {
      writeFile(file, bytes);
    } catch (IOException e) {
      Log.e("Cannot write storage of a mini app", e);
      throw new StorageException(ERROR_UNKNOWN);
    }
    if (secure) {
      touchConfig(storageId);
    }
  }

  // Secure storage owners

  /**
   * Storage id of the current account for this bot: the one that has a file for this bot, else the
   * most recently edited one. With {@code create} a new id is registered when the account has none.
   */
  private @Nullable String findStorageId (boolean create) throws StorageException {
    if (storageId != null) {
      return storageId;
    }
    JSONObject config = readConfig();
    String bestId = null;
    boolean bestHasFile = false;
    long bestEditedAt = 0;
    Iterator<String> ids = config.keys();
    while (ids.hasNext()) {
      String id = ids.next();
      JSONObject entry = config.optJSONObject(id);
      if (entry == null || entry.optLong(CONFIG_USER_ID) != accountUserId) {
        continue;
      }
      boolean hasFile = getSecureFile(id).exists();
      long editedAt = entry.optLong(CONFIG_EDITED_AT);
      boolean isBetter = bestId == null ||
        (hasFile && !bestHasFile) ||
        (hasFile == bestHasFile && editedAt > bestEditedAt);
      if (isBetter) {
        bestId = id;
        bestHasFile = hasFile;
        bestEditedAt = editedAt;
      }
    }
    if (bestId == null && create) {
      bestId = UUID.randomUUID().toString();
      long now = System.currentTimeMillis();
      JSONObject entry = new JSONObject();
      try {
        entry.put(CONFIG_USER_ID, accountUserId);
        entry.put(CONFIG_USER_NAME, accountUserName);
        entry.put(CONFIG_CREATED_AT, now);
        entry.put(CONFIG_EDITED_AT, now);
        config.put(bestId, entry);
      } catch (JSONException e) {
        throw new StorageException(ERROR_UNKNOWN);
      }
      writeConfig(config);
    }
    storageId = bestId;
    return bestId;
  }

  private void touchConfig (@Nullable String id) {
    if (id == null) {
      return;
    }
    try {
      JSONObject config = readConfig();
      JSONObject entry = config.optJSONObject(id);
      if (entry != null) {
        entry.put(CONFIG_USER_NAME, accountUserName);
        entry.put(CONFIG_EDITED_AT, System.currentTimeMillis());
        writeConfig(config);
      }
    } catch (JSONException | StorageException e) {
      Log.w("Cannot update secure storage config of mini apps", e);
    }
  }

  private List<RestoreCandidate> findRestoreCandidates (String key, long[] activeUserIds) {
    List<RestoreCandidate> candidates = new ArrayList<>();
    JSONObject config = readConfig();
    Iterator<String> ids = config.keys();
    while (ids.hasNext()) {
      String id = ids.next();
      JSONObject entry = config.optJSONObject(id);
      if (entry == null) {
        continue;
      }
      long userId = entry.optLong(CONFIG_USER_ID);
      if (userId == accountUserId || contains(activeUserIds, userId)) {
        continue;
      }
      File file = getSecureFile(id);
      if (!file.exists()) {
        continue;
      }
      try {
        if (valueOf(readJsonFile(file, true).opt(key)) != null) {
          candidates.add(new RestoreCandidate(
            id,
            userId,
            entry.optString(CONFIG_USER_NAME),
            entry.optLong(CONFIG_CREATED_AT)
          ));
        }
      } catch (GeneralSecurityException | IOException | JSONException e) {
        Log.w("Skipping unreadable secure storage of mini apps", e);
      }
    }
    return candidates;
  }

  private static long[] collectActiveUserIds () {
    long[] userIds = new long[8];
    int count = 0;
    for (TdlibAccount account : TdlibManager.instance()) {
      long userId = account.getKnownUserId();
      if (userId == 0) {
        continue;
      }
      if (count == userIds.length) {
        userIds = Arrays.copyOf(userIds, count * 2);
      }
      userIds[count++] = userId;
    }
    return Arrays.copyOf(userIds, count);
  }

  private static boolean contains (long[] array, long value) {
    for (long item : array) {
      if (item == value) {
        return true;
      }
    }
    return false;
  }

  private static JSONObject readConfig () {
    File file = getConfigFile();
    if (!file.exists()) {
      return new JSONObject();
    }
    try {
      return readJsonFile(file, false);
    } catch (GeneralSecurityException | IOException | JSONException e) {
      Log.e("Cannot read secure storage config of mini apps", e);
      return new JSONObject();
    }
  }

  private static void writeConfig (JSONObject config) throws StorageException {
    try {
      writeFile(getConfigFile(), config.toString().getBytes(UTF_8));
    } catch (IOException e) {
      Log.e("Cannot write secure storage config of mini apps", e);
      throw new StorageException(ERROR_UNKNOWN);
    }
  }

  // Files

  private static File getDirectory () {
    return new File(AppContext.get().getFilesDir(), DIRECTORY_NAME);
  }

  private static File getConfigFile () {
    return new File(getDirectory(), SECURE_CONFIG_NAME);
  }

  private File getDeviceFile () {
    return new File(getDirectory(), accountUserId + "_" + botUserId);
  }

  private File getSecureFile (String id) {
    return new File(getDirectory(), id + "_" + botUserId + SECURE_FILE_SUFFIX);
  }

  /** A file over the size limit counts as empty, as in the official client. */
  private static JSONObject readJsonFile (File file, boolean encrypted)
    throws GeneralSecurityException, IOException, JSONException {
    long maxLength = encrypted ? MAX_SIZE + MAX_SECURE_FILE_OVERHEAD : MAX_SIZE;
    if (file.length() > maxLength) {
      return new JSONObject();
    }
    byte[] bytes = readFile(file);
    if (encrypted) {
      bytes = decrypt(bytes);
    }
    if (bytes.length == 0) {
      return new JSONObject();
    }
    return new JSONObject(new String(bytes, UTF_8));
  }

  private static byte[] readFile (File file) throws IOException {
    byte[] bytes = new byte[(int) file.length()];
    int offset = 0;
    FileInputStream in = new FileInputStream(file);
    try {
      while (offset < bytes.length) {
        int read = in.read(bytes, offset, bytes.length - offset);
        if (read < 0) {
          break;
        }
        offset += read;
      }
    } finally {
      in.close();
    }
    return offset == bytes.length ? bytes : Arrays.copyOf(bytes, offset);
  }

  /** Writes through a temporary file, so that a failure never leaves a truncated storage. */
  private static void writeFile (File file, byte[] bytes) throws IOException {
    File directory = file.getParentFile();
    if (directory != null && !directory.exists() && !directory.mkdirs()) {
      throw new IOException("Cannot create " + directory);
    }
    File temporaryFile = new File(directory, file.getName() + ".tmp");
    FileOutputStream out = new FileOutputStream(temporaryFile);
    try {
      out.write(bytes);
      out.getFD().sync();
    } finally {
      out.close();
    }
    if (!temporaryFile.renameTo(file)) {
      deleteFile(temporaryFile);
      throw new IOException("Cannot replace " + file);
    }
  }

  private static void deleteFile (File file) {
    if (file.exists() && !file.delete()) {
      Log.w("Cannot delete %s", file);
    }
  }

  // Encryption: [1 byte IV length][IV][AES-GCM ciphertext with a 128-bit tag]

  private static byte[] encrypt (byte[] bytes) throws GeneralSecurityException, IOException {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return encryptImpl(bytes);
    } else {
      throw new GeneralSecurityException("Android Keystore is unavailable");
    }
  }

  private static byte[] decrypt (byte[] bytes) throws GeneralSecurityException, IOException {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return decryptImpl(bytes);
    } else {
      throw new GeneralSecurityException("Android Keystore is unavailable");
    }
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private static byte[] encryptImpl (byte[] bytes) throws GeneralSecurityException, IOException {
    Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
    cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
    byte[] iv = cipher.getIV();
    byte[] encrypted = cipher.doFinal(bytes);
    byte[] result = new byte[1 + iv.length + encrypted.length];
    result[0] = (byte) iv.length;
    System.arraycopy(iv, 0, result, 1, iv.length);
    System.arraycopy(encrypted, 0, result, 1 + iv.length, encrypted.length);
    return result;
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private static byte[] decryptImpl (byte[] bytes) throws GeneralSecurityException, IOException {
    if (bytes.length == 0) {
      throw new GeneralSecurityException("Empty file");
    }
    int ivLength = bytes[0] & 0xff;
    int offset = 1 + ivLength;
    if (ivLength == 0 || offset > bytes.length) {
      throw new GeneralSecurityException("Malformed file");
    }
    Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
    GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, bytes, 1, ivLength);
    cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec);
    return cipher.doFinal(bytes, offset, bytes.length - offset);
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private static SecretKey getSecretKey () throws GeneralSecurityException, IOException {
    KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
    keyStore.load(null);
    Key key = keyStore.getKey(KEY_ALIAS, null);
    if (key instanceof SecretKey) {
      return (SecretKey) key;
    }
    KeyGenerator generator =
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
    generator.init(new KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .build()
    );
    return generator.generateKey();
  }

  private static final class RestoreCandidate {
    final String storageId;
    final long userId;
    final String userName;
    final long createdAt;

    RestoreCandidate (String storageId, long userId, String userName, long createdAt) {
      this.storageId = storageId;
      this.userId = userId;
      this.userName = userName;
      this.createdAt = createdAt;
    }

    String describe () {
      String name = userName.isEmpty() ? Long.toString(userId) : userName;
      if (createdAt <= 0) {
        return name;
      }
      String date = Lang.dateYearFull(createdAt, TimeUnit.MILLISECONDS);
      return name + "\n" + Lang.getString(R.string.vg_BotRestoreStorageCreatedAt, date);
    }
  }

  private static final class StorageException extends Exception {
    final String error;

    StorageException (String error) {
      super(error);
      this.error = error;
    }
  }
}
