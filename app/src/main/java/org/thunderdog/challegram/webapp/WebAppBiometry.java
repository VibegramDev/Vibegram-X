/*
 * This file is a part of Vibegram X, a fork of Telegram X
 * Based on BotBiometry from Telegram for Android (https://github.com/DrKLO/Telegram), GPL-2.0-or-later.
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

import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.biometric.BiometricPrompt;

import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.BiometricAuthentication;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.unsorted.AppContext;
import org.thunderdog.challegram.unsorted.Settings;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.util.Collections;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;

import me.vkryl.core.lambda.RunnableData;
import me.vkryl.leveldb.LevelDB;

/**
 * Biometry of a mini app ({@code web_app_biometry_*}), per (account user, bot).
 *
 * <p>The token is encrypted with a per-bot Keystore key that needs a strong biometric
 * authentication for every operation, so only the cipher authenticated by the prompt of the request
 * can use it. State lives in the TGX pmc under {@code webapp_biometry_<accountUserId>_<botId>_*}:
 * {@code _granted}, {@code _token} (hex ciphertext) with {@code _iv}, {@code _requested} and
 * {@code _device_id}. Below Android 6 biometry is reported as unavailable.
 *
 * <p>All methods except {@link #onLoggedOut} are called on the UI thread.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public final class WebAppBiometry {
  public static final String
    STATUS_AUTHORIZED = "authorized",
    STATUS_FAILED = "failed",
    STATUS_UPDATED = "updated",
    STATUS_REMOVED = "removed";

  public interface StatusCallback {
    void onResult (String status, @Nullable String token);
  }

  private static final String PMC_PREFIX = "webapp_biometry_";
  private static final String KEY_ALIAS_PREFIX = "vg_webapp_biometry_";
  private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
  private static final String CIPHER_TRANSFORMATION = "AES/CBC/PKCS7Padding";
  private static final int DEVICE_ID_BYTE_COUNT = 32;
  private static final Charset UTF_8 = Charset.forName("UTF-8");

  // The access dialog or the prompt on screen, for every bot: a page that repeats requests can't
  // stack them, and a second request can't take over the callback of the first one.
  private static @Nullable ViewController<?> promptOwner;

  private final String keyPrefix;
  private final String keyAlias;

  public WebAppBiometry (Tdlib tdlib, long botUserId) {
    long accountUserId = tdlib.myUserId(true);
    this.keyPrefix = PMC_PREFIX + accountUserId + "_" + botUserId;
    this.keyAlias = KEY_ALIAS_PREFIX + accountUserId + "_" + botUserId;
    migrateLegacyState();
  }

  public static boolean isAccessGranted (Tdlib tdlib, long botUserId) {
    return new WebAppBiometry(tdlib, botUserId).isGranted();
  }

  /** Forgets the grant, the token and the key; the bot may ask for access again. */
  public static void revokeAccess (Tdlib tdlib, long botUserId) {
    WebAppBiometry biometry = new WebAppBiometry(tdlib, botUserId);
    pmc().remove(biometry.keyPrefix + "_granted");
    pmc().remove(biometry.keyPrefix + "_requested");
    biometry.removeToken();
    biometry.deleteKey();
  }

  /** Called on any thread. */
  public static void onLoggedOut (long userId) {
    pmc().removeByPrefix(PMC_PREFIX + userId + "_");
    String aliasPrefix = KEY_ALIAS_PREFIX + userId + "_";
    Background.instance().post(() -> {
      try {
        KeyStore keyStore = loadKeyStore();
        for (String alias : Collections.list(keyStore.aliases())) {
          if (alias.startsWith(aliasPrefix)) {
            keyStore.deleteEntry(alias);
          }
        }
      } catch (GeneralSecurityException | IOException e) {
        Log.w("Cannot delete biometry keys of mini apps", e);
      }
    });
  }

  // State

  private static LevelDB pmc () {
    return Settings.instance().pmc();
  }

  private boolean isGranted () {
    return pmc().getBoolean(keyPrefix + "_granted", false);
  }

  private void setGranted () {
    setRequested();
    pmc().putBoolean(keyPrefix + "_granted", true);
  }

  private boolean isRequested () {
    return pmc().getBoolean(keyPrefix + "_requested", false);
  }

  private void setRequested () {
    pmc().putBoolean(keyPrefix + "_requested", true);
  }

  private @Nullable String getToken () {
    return pmc().getString(keyPrefix + "_token", null);
  }

  private void saveToken (byte[] encrypted, byte[] iv) {
    pmc().putString(keyPrefix + "_token", U.bytesToHex(encrypted));
    pmc().putString(keyPrefix + "_iv", U.bytesToHex(iv));
  }

  private void removeToken () {
    pmc().remove(keyPrefix + "_token");
    pmc().remove(keyPrefix + "_iv");
  }

  /**
   * Older versions kept the grant in the token itself ({@code ""} = granted without a token) and a
   * denial in {@code _disabled}.
   */
  private void migrateLegacyState () {
    String disabledKey = keyPrefix + "_disabled";
    boolean disabled = pmc().getBoolean(disabledKey, false);
    String token = getToken();
    if (!disabled && (token == null || isGranted())) {
      return;
    }
    if (token != null && !disabled) {
      pmc().putBoolean(keyPrefix + "_granted", true);
    }
    if (token != null && (disabled || token.isEmpty())) {
      removeToken();
    }
    pmc().remove(disabledKey);
  }

  private String getDeviceId () {
    String key = keyPrefix + "_device_id";
    String deviceId = pmc().getString(key, null);
    if (deviceId == null || deviceId.isEmpty()) {
      byte[] bytes = new byte[DEVICE_ID_BYTE_COUNT];
      new SecureRandom().nextBytes(bytes);
      deviceId = U.bytesToHex(bytes);
      pmc().putString(key, deviceId);
    }
    return deviceId;
  }

  private static boolean isAvailable () {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
      BiometricAuthentication.isStrongAvailable(true);
  }

  private static boolean isPromptShown () {
    return promptOwner != null && !promptOwner.isDestroyed();
  }

  private static String getBiometryType () {
    PackageManager packageManager = AppContext.get().getPackageManager();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
      packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
      return "finger";
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (
      packageManager.hasSystemFeature(PackageManager.FEATURE_FACE) ||
      packageManager.hasSystemFeature(PackageManager.FEATURE_IRIS)
    )) {
      return "face";
    }
    return "unknown";
  }

  /** {@code biometry_info_received} payload. */
  public JSONObject getInfo () {
    JSONObject info = new JSONObject();
    try {
      boolean available = isAvailable();
      info.put("available", available);
      if (available) {
        info.put("type", getBiometryType());
      }
      info.put("access_requested", isRequested());
      info.put("access_granted", isGranted());
      info.put("token_saved", getToken() != null);
      info.put("device_id", getDeviceId());
    } catch (JSONException e) {
      Log.e("Cannot build biometry info", e);
    }
    return info;
  }

  // Requests

  /** {@code web_app_biometry_request_access}: {@code after} sends {@link #getInfo()}. */
  public void requestAccess (
    ViewController<?> context,
    String botName,
    @Nullable String reason,
    Runnable after
  ) {
    if (isRequested() || isGranted() || !isAvailable() || isPromptShown() || context.isDestroyed()) {
      after.run();
      return;
    }
    CharSequence question = Lang.getStringBold(R.string.vg_BotAllowBiometryMessage, botName);
    boolean hasReason = reason != null && !reason.trim().isEmpty();
    AlertPopup.Builder builder = new AlertPopup.Builder(context.context());
    if (hasReason) {
      builder.setTitle(question).setMessage(reason);
    } else {
      builder.setTitle(Lang.getString(R.string.vg_BotAllowBiometryTitle)).setMessage(question);
    }
    Runnable onDenied = () -> {
      promptOwner = null;
      setRequested();
      after.run();
    };
    builder
      .setPositiveButton(Lang.getString(R.string.Allow), (dialog, which) -> {
        setRequested();
        authenticate(context, botName, null, newCipher(Cipher.ENCRYPT_MODE, null), cipher -> {
          setGranted();
          after.run();
        }, after);
      })
      .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> onDenied.run())
      .setOnCancelListener(dialog -> onDenied.run());
    promptOwner = context;
    if (context.showAlert(builder) == null) {
      promptOwner = null;
      after.run();
    }
  }

  /**
   * {@code web_app_biometry_request_auth}: status {@code authorized} with the token ({@code ""}
   * when none is saved), or {@code failed}.
   */
  public void requestAuth (
    ViewController<?> context,
    String botName,
    @Nullable String reason,
    StatusCallback callback
  ) {
    Runnable onFailure = () -> callback.onResult(STATUS_FAILED, null);
    if (!isGranted() || !isAvailable() || isPromptShown()) {
      onFailure.run();
      return;
    }
    String token = getToken();
    if (token == null) {
      // Nothing to decrypt: the prompt only confirms the user.
      authenticate(context, botName, reason, newCipher(Cipher.ENCRYPT_MODE, null), cipher ->
        callback.onResult(STATUS_AUTHORIZED, ""), onFailure
      );
      return;
    }
    byte[] encrypted = U.fromHex(token);
    byte[] iv = U.fromHex(pmc().getString(keyPrefix + "_iv", null));
    Cipher cipher = encrypted != null && iv != null ? newCipher(Cipher.DECRYPT_MODE, iv) : null;
    authenticate(context, botName, reason, cipher, authenticatedCipher -> {
      String plain;
      try {
        plain = new String(authenticatedCipher.doFinal(encrypted), UTF_8);
      } catch (GeneralSecurityException | IllegalStateException e) {
        Log.e("Cannot decrypt biometry token", e);
        onFailure.run();
        return;
      }
      callback.onResult(STATUS_AUTHORIZED, plain);
    }, onFailure);
  }

  /**
   * {@code web_app_biometry_update_token}: status {@code updated}, {@code removed} or
   * {@code failed}.
   */
  public void updateToken (
    ViewController<?> context,
    String botName,
    String token,
    @Nullable String reason,
    StatusCallback callback
  ) {
    Runnable onFailure = () -> callback.onResult(STATUS_FAILED, null);
    if (!isGranted() || !isAvailable() || isPromptShown()) {
      onFailure.run();
      return;
    }
    authenticate(context, botName, reason, newCipher(Cipher.ENCRYPT_MODE, null), cipher -> {
      if (token.isEmpty()) {
        removeToken();
        callback.onResult(STATUS_REMOVED, null);
        return;
      }
      byte[] encrypted;
      try {
        encrypted = cipher.doFinal(token.getBytes(UTF_8));
      } catch (GeneralSecurityException | IllegalStateException e) {
        Log.e("Cannot encrypt biometry token", e);
        onFailure.run();
        return;
      }
      saveToken(encrypted, cipher.getIV());
      callback.onResult(STATUS_UPDATED, null);
    }, onFailure);
  }

  /** Shows the prompt bound to {@code cipher}; fails at once without a cipher. */
  private static void authenticate (
    ViewController<?> context,
    String botName,
    @Nullable String reason,
    @Nullable Cipher cipher,
    RunnableData<Cipher> onSuccess,
    Runnable onFailure
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || cipher == null || context.isDestroyed()) {
      promptOwner = null;
      onFailure.run();
      return;
    }
    promptOwner = context;
    BiometricAuthentication.authenticate(
      context.context(),
      botName,
      reason,
      Lang.getString(R.string.CancelBiometrics),
      true,
      new BiometricPrompt.CryptoObject(cipher),
      new BiometricAuthentication.Callback() {
        @Override
        public void onAuthenticated (BiometricPrompt.AuthenticationResult result, boolean strong) {
          promptOwner = null;
          BiometricPrompt.CryptoObject cryptoObject = result.getCryptoObject();
          Cipher authenticatedCipher = cryptoObject != null ? cryptoObject.getCipher() : null;
          if (authenticatedCipher != null) {
            onSuccess.runWithData(authenticatedCipher);
          } else {
            onFailure.run();
          }
        }

        @Override
        public void onAuthenticationError (CharSequence message, boolean isFatal) {
          // Not fatal: a biometric that did not match, the prompt stays open.
          if (isFatal) {
            promptOwner = null;
            onFailure.run();
          }
        }
      }
    );
  }

  // Keystore

  /**
   * @param iv null for encryption
   * @return null when the key can't be used; for decryption the token is then dropped, as nothing
   * can decrypt it anymore
   */
  private @Nullable Cipher newCipher (int mode, @Nullable byte[] iv) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return null;
    }
    try {
      Cipher cipher = newCipherImpl(mode, iv);
      if (cipher == null) {
        removeToken();
      }
      return cipher;
    } catch (GeneralSecurityException | IOException | ProviderException e) {
      // instanceof instead of a catch clause: an API 23 exception class in a catch clause can fail
      // verification of the whole class on Android 5.
      if (!(e instanceof KeyPermanentlyInvalidatedException)) {
        Log.e("Cannot create biometry cipher", e);
        return null;
      }
      // A biometric was enrolled or removed: the token can't be decrypted anymore.
      Log.w("Biometry key of a mini app was invalidated", e);
      deleteKey();
      if (mode == Cipher.DECRYPT_MODE) {
        removeToken();
        return null;
      }
      try {
        return newCipherImpl(mode, null);
      } catch (GeneralSecurityException | IOException | ProviderException retryError) {
        Log.e("Cannot create biometry cipher", retryError);
        return null;
      }
    }
  }

  /** Null for decryption when there is no key. */
  @RequiresApi(Build.VERSION_CODES.M)
  private @Nullable Cipher newCipherImpl (int mode, @Nullable byte[] iv)
    throws GeneralSecurityException, IOException {
    SecretKey key = getSecretKey(mode == Cipher.ENCRYPT_MODE);
    if (key == null) {
      return null;
    }
    Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
    if (iv != null) {
      cipher.init(mode, key, new IvParameterSpec(iv));
    } else {
      cipher.init(mode, key);
    }
    return cipher;
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private @Nullable SecretKey getSecretKey (boolean create)
    throws GeneralSecurityException, IOException {
    KeyStore keyStore = loadKeyStore();
    Key key = keyStore.getKey(keyAlias, null);
    if (key instanceof SecretKey) {
      if (!isTimeBound((SecretKey) key)) {
        return (SecretKey) key;
      }
      // Older versions made keys that any strong authentication of the last 60 seconds unlocked,
      // not only the prompt of the request. Such a key can't be bound to a prompt, so it is replaced
      // and its token is lost.
      keyStore.deleteEntry(keyAlias);
    }
    if (!create) {
      return null;
    }
    KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
      keyAlias,
      KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
      .setUserAuthenticationRequired(true);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      // Timeout 0: every operation needs its own authentication, as before Android 11 by default.
      spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      spec.setInvalidatedByBiometricEnrollment(true);
    }
    KeyGenerator generator =
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
    generator.init(spec.build());
    return generator.generateKey();
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private static boolean isTimeBound (SecretKey key) throws GeneralSecurityException {
    SecretKeyFactory factory = SecretKeyFactory.getInstance(key.getAlgorithm(), KEYSTORE_PROVIDER);
    KeyInfo info = (KeyInfo) factory.getKeySpec(key, KeyInfo.class);
    return info.getUserAuthenticationValidityDurationSeconds() > 0;
  }

  private static KeyStore loadKeyStore () throws GeneralSecurityException, IOException {
    KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
    keyStore.load(null);
    return keyStore;
  }

  private void deleteKey () {
    try {
      loadKeyStore().deleteEntry(keyAlias);
    } catch (GeneralSecurityException | IOException e) {
      Log.w("Cannot delete biometry key", e);
    }
  }
}
