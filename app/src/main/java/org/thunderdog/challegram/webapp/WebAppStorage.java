/*
 * This file is a part of Vibegram X, a fork of Telegram X
 * Based on BotStorage from Telegram for Android (https://github.com/DrKLO/Telegram), GPL-2.0-or-later.
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
import org.thunderdog.challegram.core.BaseThread;
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
import java.security.ProviderException;
import java.security.UnrecoverableKeyException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Device storage and secure storage of a mini app, per (account user, bot).
 *
 * <p>Files live in {@code <filesDir>/apps_storage/}: {@code <userId>_<botId>} (plain JSON) and
 * {@code <storageId>_<botId>_s} (AES-GCM encrypted JSON), where {@code secure_config.json} maps
 * every storage id to its owner, one id per account. Secure storages of accounts that logged out
 * are kept so that {@link #restore} can move them to another account.
 *
 * <p>Every operation reads the file again, so instances of the same storage (one per open mini app)
 * never overwrite each other with stale data. All file work runs on one storage thread, which
 * serializes the operations of every instance; the public methods except {@link #onLoggedOut} are
 * called on the UI thread.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
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

  private static final class StorageThread {
    // Not the shared Background thread: JSON of up to 5 MB, Keystore crypto and fsync would delay
    // unrelated work queued there.
    static final BaseThread INSTANCE = new BaseThread("WebAppStorage");
  }

  private static void post (Runnable task) {
    StorageThread.INSTANCE.post(task, 0);
  }

  private final long accountUserId;
  private final String accountUserName;
  private final long botUserId;
  private final boolean secure;

  public WebAppStorage (Tdlib tdlib, long botUserId, boolean secure) {
    this.accountUserId = tdlib.myUserId(true);
    TdApi.User user = tdlib.myUser();
    this.accountUserName = user != null ? TD.getUserName(user) : "";
    this.botUserId = botUserId;
    this.secure = secure;
  }

  /**
   * Deletes the device storages of a user that logs out; called on any thread. Secure storages are
   * kept: {@link #restore} offers them to the other accounts of this device.
   */
  public static void onLoggedOut (long userId) {
    post(() -> {
      File[] files = getDirectory().listFiles();
      if (files == null) {
        return;
      }
      String prefix = userId + "_";
      for (File file : files) {
        String name = file.getName();
        // Secure files start with a storage id (a UUID), which never looks like "<digits>_".
        if (name.startsWith(prefix) && !name.endsWith(SECURE_FILE_SUFFIX)) {
          deleteFile(file);
        }
      }
    });
  }

  /** @param value null removes the key */
  public void save (String key, @Nullable String value, Callback callback) {
    post(() -> {
      String error = null;
      try {
        saveImpl(key, value);
      } catch (StorageException e) {
        error = e.error;
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
    post(() -> {
      String value = null, error = null;
      boolean canRestore = false;
      try {
        checkSupported();
        checkKey(key);
        JSONObject data = loadData();
        value = valueOf(data.opt(key));
        // Restoring is possible only into an empty storage, see restoreImpl.
        if (value == null && activeUserIds != null && data.length() == 0) {
          canRestore = !findRestoreCandidates(key, activeUserIds, true).isEmpty();
        }
      } catch (StorageException e) {
        error = e.error;
      }
      deliver(callback, value, error, canRestore);
    });
  }

  public void clear (Callback callback) {
    post(() -> {
      String error = null;
      try {
        clearImpl();
      } catch (StorageException e) {
        error = e.error;
      }
      deliver(callback, null, error, false);
    });
  }

  /**
   * Secure storage only: offers the storages that accounts no longer logged in on this device kept
   * for this bot and that have {@code key}, moves the chosen one to the current account and returns
   * the value of {@code key}.
   */
  public void restore (ViewController<?> context, String botName, String key, Callback callback) {
    final long[] activeUserIds = collectActiveUserIds();
    post(() -> {
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
        candidates = findRestoreCandidates(key, activeUserIds, false);
        if (candidates.isEmpty()) {
          throw new StorageException(ERROR_RESTORE_UNAVAILABLE);
        }
      } catch (StorageException e) {
        deliver(callback, null, e.error, false);
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
    post(() -> {
      String value = null, error = null;
      try {
        value = restoreImpl(candidate, key);
      } catch (StorageException e) {
        error = e.error;
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

  // Operations (storage thread)

  private void saveImpl (String key, @Nullable String value) throws StorageException {
    checkSupported();
    checkKey(key);
    JSONObject data = loadData();
    if (value == null) {
      if (data.remove(key) == null) {
        return;
      }
    } else {
      if ((long) key.length() + value.length() > MAX_SIZE) {
        throw new StorageException(ERROR_QUOTA_EXCEEDED);
      }
      if (secure && !data.has(key) && data.length() >= MAX_SECURE_KEY_COUNT) {
        throw new StorageException(ERROR_QUOTA_EXCEEDED);
      }
      try {
        data.put(key, value);
      } catch (JSONException e) {
        throw new StorageException(ERROR_VALUE_INVALID);
      }
    }
    writeData(data);
  }

  private void clearImpl () throws StorageException {
    checkSupported();
    File file;
    if (secure) {
      String id = findStorageId(readConfig());
      if (id == null) {
        return;
      }
      file = getSecureFile(id);
    } else {
      file = getDeviceFile();
    }
    if (!deleteFile(file)) {
      throw new StorageException(ERROR_UNKNOWN);
    }
  }

  private String restoreImpl (RestoreCandidate candidate, String key) throws StorageException {
    checkSupported();
    if (loadData().length() > 0) {
      throw new StorageException(ERROR_STORAGE_NOT_EMPTY);
    }
    File source = getSecureFile(candidate.storageId);
    if (!source.exists()) {
      throw new StorageException(ERROR_RESTORE_UNAVAILABLE);
    }
    // Only the file of this bot moves: the account keeps its single storage id, and what the old
    // account kept for other bots stays offered to them.
    File target = getSecureFile(requireStorageId());
    if (!source.renameTo(target)) {
      Log.e("Cannot move %s to %s", source, target);
      throw new StorageException(ERROR_UNKNOWN);
    }
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

  private JSONObject loadData () throws StorageException {
    if (!secure) {
      try {
        return readJsonFile(getDeviceFile(), false);
      } catch (JSONException e) {
        // Nothing to save from a damaged file: the next write replaces it.
        Log.e("Device storage of a mini app is damaged", e);
        return new JSONObject();
      } catch (GeneralSecurityException | IOException e) {
        Log.e("Cannot read device storage of a mini app", e);
        throw new StorageException(ERROR_UNKNOWN);
      }
    }
    String id = findStorageId(readConfig());
    if (id == null) {
      return new JSONObject();
    }
    File file = getSecureFile(id);
    try {
      return readJsonFile(file, true);
    } catch (AEADBadTagException | UnrecoverableKeyException | JSONException e) {
      // The key is lost (e.g. the Keystore was cleared) or the file is damaged: the data can't come
      // back, so the storage starts over.
      Log.e("Secure storage of a mini app can't be decrypted, resetting", e);
      deleteFile(file);
      throw new StorageException(ERROR_UNKNOWN);
    } catch (GeneralSecurityException | IOException | ProviderException e) {
      // Keystore errors may be temporary: the file is kept.
      Log.e("Cannot read secure storage of a mini app", e);
      throw new StorageException(ERROR_UNKNOWN);
    }
  }

  private void writeData (JSONObject data) throws StorageException {
    byte[] bytes = data.toString().getBytes(UTF_8);
    if (bytes.length > MAX_SIZE) {
      throw new StorageException(ERROR_QUOTA_EXCEEDED);
    }
    File file;
    if (secure) {
      file = getSecureFile(requireStorageId());
      try {
        bytes = encrypt(bytes);
      } catch (GeneralSecurityException | IOException | ProviderException e) {
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
  }

  // Secure storage owners

  private @Nullable String findStorageId (JSONObject config) throws StorageException {
    List<String> ids = new ArrayList<>();
    Iterator<String> keys = config.keys();
    while (keys.hasNext()) {
      String id = keys.next();
      JSONObject entry = config.optJSONObject(id);
      if (entry != null && entry.optLong(CONFIG_USER_ID) == accountUserId) {
        ids.add(id);
      }
    }
    if (ids.isEmpty()) {
      return null;
    }
    return ids.size() == 1 ? ids.get(0) : mergeStorageIds(config, ids);
  }

  /**
   * Older versions moved a whole storage id on restore, so an account could own several ids and
   * each bot used the most recently edited id that had its file. The files are merged into one id
   * with the same choice; the files that choice hid are deleted.
   */
  private String mergeStorageIds (JSONObject config, List<String> ids) throws StorageException {
    Collections.sort(ids, (a, b) -> Long.compare(
      config.optJSONObject(b).optLong(CONFIG_EDITED_AT),
      config.optJSONObject(a).optLong(CONFIG_EDITED_AT)
    ));
    String primaryId = ids.get(0);
    File[] files = getDirectory().listFiles();
    for (int i = 1; i < ids.size(); i++) {
      String id = ids.get(i);
      if (files != null) {
        for (File file : files) {
          String name = file.getName();
          if (!name.startsWith(id + "_") || !name.endsWith(SECURE_FILE_SUFFIX)) {
            continue;
          }
          File target = new File(file.getParentFile(), primaryId + name.substring(id.length()));
          if (target.exists()) {
            deleteFile(file);
          } else if (!file.renameTo(target)) {
            Log.w("Cannot move %s to %s", file, target);
          }
        }
      }
      config.remove(id);
    }
    writeConfig(config);
    return primaryId;
  }

  /** Registers a storage id for the account when it has none. */
  private String requireStorageId () throws StorageException {
    JSONObject config = readConfig();
    String id = findStorageId(config);
    JSONObject entry = id != null ? config.optJSONObject(id) : null;
    boolean isNameKnown = accountUserName.isEmpty() ||
      (entry != null && accountUserName.equals(entry.optString(CONFIG_USER_NAME)));
    if (entry != null && isNameKnown) {
      return id;
    }
    try {
      if (entry == null) {
        id = UUID.randomUUID().toString();
        entry = new JSONObject();
        entry.put(CONFIG_USER_ID, accountUserId);
        entry.put(CONFIG_CREATED_AT, System.currentTimeMillis());
        config.put(id, entry);
      }
      // The name is shown when another account is offered to restore this storage.
      entry.put(CONFIG_USER_NAME, accountUserName);
    } catch (JSONException e) {
      throw new StorageException(ERROR_UNKNOWN);
    }
    writeConfig(config);
    return id;
  }

  /** Decrypts only the files of this bot, and with {@code firstOnly} stops at the first match. */
  private List<RestoreCandidate> findRestoreCandidates (
    String key,
    long[] activeUserIds,
    boolean firstOnly
  ) {
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
          if (firstOnly) {
            break;
          }
        }
      } catch (GeneralSecurityException | IOException | JSONException | ProviderException e) {
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
    try {
      return readJsonFile(getConfigFile(), false);
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

  /** Writes never exceed the size limit, so a bigger file is foreign and counts as empty. */
  private static JSONObject readJsonFile (File file, boolean encrypted)
    throws GeneralSecurityException, IOException, JSONException {
    long maxLength = encrypted ? MAX_SIZE + MAX_SECURE_FILE_OVERHEAD : MAX_SIZE;
    if (!file.exists() || file.length() > maxLength) {
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

  private static boolean deleteFile (File file) {
    if (!file.exists() || file.delete()) {
      return true;
    }
    Log.w("Cannot delete %s", file);
    return false;
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
    int ivLength = bytes.length > 0 ? bytes[0] & 0xff : 0;
    int offset = 1 + ivLength;
    if (ivLength == 0 || offset > bytes.length) {
      // Damaged like a file with a wrong tag: the storage is reset.
      throw new AEADBadTagException("Malformed file");
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
