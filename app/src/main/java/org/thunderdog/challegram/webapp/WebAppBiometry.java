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

import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;

import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.unsorted.AppContext;
import org.thunderdog.challegram.unsorted.Settings;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import me.vkryl.leveldb.LevelDB;

/**
 * Biometry of a mini app ({@code web_app_biometry_*}), per (account user, bot).
 *
 * <p>The token is encrypted with a per-bot Keystore key that requires a strong biometric
 * authentication. State lives in the TGX pmc under
 * {@code webapp_biometry_<accountUserId>_<botId>_*}: {@code _token} (hex ciphertext, its presence
 * means access is granted, {@code ""} = granted without a token), {@code _iv},
 * {@code _requested}, {@code _disabled} and {@code _device_id}. Below Android 6 biometry is
 * reported as unavailable.
 *
 * <p>All methods are called on the UI thread.
 */
public final class WebAppBiometry {
  public static final String
    STATUS_AUTHORIZED = "authorized",
    STATUS_FAILED = "failed",
    STATUS_UPDATED = "updated",
    STATUS_REMOVED = "removed";

  public interface StatusCallback {
    void onResult (String status, @Nullable String token);
  }

  private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
  private static final String CIPHER_TRANSFORMATION = "AES/CBC/PKCS7Padding";
  private static final int AUTHENTICATION_VALIDITY_SECONDS = 60;
  private static final int DEVICE_ID_BYTE_COUNT = 32;
  private static final Charset UTF_8 = Charset.forName("UTF-8");

  private final String keyPrefix;
  private final String keyAlias;

  public WebAppBiometry (Tdlib tdlib, long botUserId) {
    long accountUserId = tdlib.myUserId(true);
    this.keyPrefix = "webapp_biometry_" + accountUserId + "_" + botUserId;
    this.keyAlias = "vg_webapp_biometry_" + accountUserId + "_" + botUserId;
  }

  // State

  private static LevelDB pmc () {
    return Settings.instance().pmc();
  }

  private @Nullable String getStoredToken () {
    return pmc().getString(keyPrefix + "_token", null);
  }

  private boolean isGranted () {
    return getStoredToken() != null;
  }

  private boolean isRequested () {
    return pmc().getBoolean(keyPrefix + "_requested", false);
  }

  private void setRequested () {
    pmc().putBoolean(keyPrefix + "_requested", true);
  }

  private boolean isDisabled () {
    return pmc().getBoolean(keyPrefix + "_disabled", false);
  }

  private void setDisabled (boolean disabled) {
    if (disabled) {
      pmc().putBoolean(keyPrefix + "_disabled", true);
    } else {
      pmc().remove(keyPrefix + "_disabled");
    }
  }

  private void saveToken (String encryptedHex, @Nullable String ivHex) {
    pmc().putString(keyPrefix + "_token", encryptedHex);
    if (ivHex != null) {
      pmc().putString(keyPrefix + "_iv", ivHex);
    } else {
      pmc().remove(keyPrefix + "_iv");
    }
  }

  private String getDeviceId () {
    String key = keyPrefix + "_device_id";
    String deviceId = pmc().getString(key, null);
    if (deviceId == null || deviceId.isEmpty()) {
      byte[] bytes = new byte[DEVICE_ID_BYTE_COUNT];
      new SecureRandom().nextBytes(bytes);
      deviceId = toHex(bytes);
      pmc().putString(key, deviceId);
    }
    return deviceId;
  }

  public boolean isAccessGranted () {
    return isGranted() && !isDisabled();
  }

  /** The switch of a bot profile: turning it off keeps the token, as the official client does. */
  public void setAccessGranted (boolean granted) {
    setRequested();
    setDisabled(!granted);
    if (granted && !isGranted()) {
      saveToken("", null);
    }
  }

  public static boolean isAvailable () {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      try {
        int result = BiometricManager.from(AppContext.get())
          .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        return result == BiometricManager.BIOMETRIC_SUCCESS;
      } catch (Throwable t) {
        Log.w("Cannot check biometry", t);
      }
    }
    return false;
  }

  private static String getBiometryType () {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PackageManager packageManager = AppContext.get().getPackageManager();
      if (packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
        return "finger";
      }
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
      String token = getStoredToken();
      info.put("access_requested", isRequested());
      info.put("access_granted", token != null && !isDisabled());
      info.put("token_saved", token != null && !token.isEmpty());
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
    if (isRequested()) {
      after.run();
      return;
    }
    if (isGranted()) {
      setRequested();
      after.run();
      return;
    }
    if (!isAvailable() || context.isDestroyed()) {
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
    builder
      .setPositiveButton(Lang.getString(R.string.Allow), (dialog, which) -> {
        setRequested();
        authenticate(context, botName, null, null, new PromptCallback() {
          @Override
          public void onAuthenticated (@Nullable Cipher cipher) {
            setDisabled(false);
            if (!isGranted()) {
              saveToken("", null);
            }
            after.run();
          }

          @Override
          public void onFailed () {
            after.run();
          }
        });
      })
      .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> {
        setRequested();
        setDisabled(true);
        after.run();
      })
      .setOnCancelListener(dialog -> {
        setRequested();
        after.run();
      });
    if (context.showAlert(builder) == null) {
      after.run();
    }
  }

  /**
   * {@code web_app_biometry_request_auth}: status {@code authorized} with the token, or
   * {@code failed}.
   */
  public void requestAuth (
    ViewController<?> context,
    String botName,
    @Nullable String reason,
    StatusCallback callback
  ) {
    String storedToken = getStoredToken();
    if (storedToken == null || isDisabled() || !isAvailable()) {
      callback.onResult(STATUS_FAILED, null);
      return;
    }
    if (storedToken.isEmpty()) {
      authenticate(context, botName, reason, null, new PromptCallback() {
        @Override
        public void onAuthenticated (@Nullable Cipher cipher) {
          callback.onResult(STATUS_AUTHORIZED, "");
        }

        @Override
        public void onFailed () {
          callback.onResult(STATUS_FAILED, null);
        }
      });
      return;
    }
    byte[] encrypted = fromHex(storedToken);
    byte[] iv = fromHex(pmc().getString(keyPrefix + "_iv", null));
    if (encrypted == null || iv == null) {
      callback.onResult(STATUS_FAILED, null);
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      // Time-bound key: the cipher can be initialized only after the authentication.
      authenticate(context, botName, reason, null, new PromptCallback() {
        @Override
        public void onAuthenticated (@Nullable Cipher cipher) {
          finishDecryption(newCipher(Cipher.DECRYPT_MODE, iv), encrypted, callback);
        }

        @Override
        public void onFailed () {
          callback.onResult(STATUS_FAILED, null);
        }
      });
    } else {
      Cipher cipher = newCipher(Cipher.DECRYPT_MODE, iv);
      if (cipher == null) {
        callback.onResult(STATUS_FAILED, null);
        return;
      }
      authenticate(context, botName, reason, cipher, new PromptCallback() {
        @Override
        public void onAuthenticated (@Nullable Cipher authenticatedCipher) {
          finishDecryption(authenticatedCipher, encrypted, callback);
        }

        @Override
        public void onFailed () {
          callback.onResult(STATUS_FAILED, null);
        }
      });
    }
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
    if (!isAccessGranted() || !isAvailable()) {
      callback.onResult(STATUS_FAILED, null);
      return;
    }
    if (token.isEmpty()) {
      authenticate(context, botName, reason, null, new PromptCallback() {
        @Override
        public void onAuthenticated (@Nullable Cipher cipher) {
          saveToken("", null);
          callback.onResult(STATUS_REMOVED, null);
        }

        @Override
        public void onFailed () {
          callback.onResult(STATUS_FAILED, null);
        }
      });
      return;
    }
    byte[] plain = token.getBytes(UTF_8);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      authenticate(context, botName, reason, null, new PromptCallback() {
        @Override
        public void onAuthenticated (@Nullable Cipher cipher) {
          finishEncryption(newCipher(Cipher.ENCRYPT_MODE, null), plain, callback);
        }

        @Override
        public void onFailed () {
          callback.onResult(STATUS_FAILED, null);
        }
      });
    } else {
      Cipher cipher = newCipher(Cipher.ENCRYPT_MODE, null);
      if (cipher == null) {
        callback.onResult(STATUS_FAILED, null);
        return;
      }
      authenticate(context, botName, reason, cipher, new PromptCallback() {
        @Override
        public void onAuthenticated (@Nullable Cipher authenticatedCipher) {
          finishEncryption(authenticatedCipher, plain, callback);
        }

        @Override
        public void onFailed () {
          callback.onResult(STATUS_FAILED, null);
        }
      });
    }
  }

  private static void finishDecryption (
    @Nullable Cipher cipher,
    byte[] encrypted,
    StatusCallback callback
  ) {
    if (cipher == null) {
      callback.onResult(STATUS_FAILED, null);
      return;
    }
    try {
      byte[] plain = cipher.doFinal(encrypted);
      callback.onResult(STATUS_AUTHORIZED, new String(plain, UTF_8));
    } catch (GeneralSecurityException | IllegalStateException e) {
      Log.e("Cannot decrypt biometry token", e);
      callback.onResult(STATUS_FAILED, null);
    }
  }

  private void finishEncryption (@Nullable Cipher cipher, byte[] plain, StatusCallback callback) {
    if (cipher == null) {
      callback.onResult(STATUS_FAILED, null);
      return;
    }
    try {
      byte[] encrypted = cipher.doFinal(plain);
      saveToken(toHex(encrypted), toHex(cipher.getIV()));
      callback.onResult(STATUS_UPDATED, null);
    } catch (GeneralSecurityException | IllegalStateException e) {
      Log.e("Cannot encrypt biometry token", e);
      callback.onResult(STATUS_FAILED, null);
    }
  }

  // Prompt

  private interface PromptCallback {
    /** @param cipher the authenticated cipher of the crypto object, if one was passed */
    void onAuthenticated (@Nullable Cipher cipher);
    void onFailed ();
  }

  private static void authenticate (
    ViewController<?> context,
    String botName,
    @Nullable String reason,
    @Nullable Cipher cipher,
    PromptCallback callback
  ) {
    if (context.isDestroyed()) {
      callback.onFailed();
      return;
    }
    BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
      .setTitle(botName)
      .setNegativeButtonText(Lang.getString(R.string.vg_Back))
      .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
    if (reason != null && !reason.trim().isEmpty()) {
      info.setDescription(reason);
    }
    BiometricPrompt.AuthenticationCallback authenticationCallback =
      new BiometricPrompt.AuthenticationCallback() {
        @Override
        public void onAuthenticationSucceeded (
          @NonNull BiometricPrompt.AuthenticationResult result
        ) {
          BiometricPrompt.CryptoObject cryptoObject = result.getCryptoObject();
          callback.onAuthenticated(cryptoObject != null ? cryptoObject.getCipher() : null);
        }

        @Override
        public void onAuthenticationError (int errorCode, @NonNull CharSequence errorText) {
          callback.onFailed();
        }

        // onAuthenticationFailed (a finger that did not match) keeps the prompt open.
      };
    try {
      BiometricPrompt prompt = new BiometricPrompt(context.context(), authenticationCallback);
      if (cipher != null) {
        prompt.authenticate(info.build(), new BiometricPrompt.CryptoObject(cipher));
      } else {
        prompt.authenticate(info.build());
      }
    } catch (Throwable t) {
      Log.e("Cannot show biometric prompt", t);
      callback.onFailed();
    }
  }

  // Keystore

  /** @param iv null for encryption */
  private @Nullable Cipher newCipher (int mode, @Nullable byte[] iv) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return null;
    }
    try {
      return newCipherImpl(mode, iv);
    } catch (Exception e) {
      // instanceof instead of a catch clause: an API 23 exception class in a catch clause can fail
      // verification of the whole class on old devices.
      boolean isInvalidated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        e instanceof KeyPermanentlyInvalidatedException;
      if (!isInvalidated) {
        Log.e("Cannot create biometry cipher", e);
        return null;
      }
      // A new fingerprint was enrolled: the token can't be decrypted anymore.
      Log.w("Biometry key of a mini app was invalidated", e);
      deleteKey();
      if (mode == Cipher.DECRYPT_MODE) {
        saveToken("", null);
        return null;
      }
      try {
        return newCipherImpl(mode, iv);
      } catch (Exception retryError) {
        Log.e("Cannot create biometry cipher", retryError);
        return null;
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private Cipher newCipherImpl (int mode, @Nullable byte[] iv) throws Exception {
    Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
    if (iv != null) {
      cipher.init(mode, getSecretKey(), new IvParameterSpec(iv));
    } else {
      cipher.init(mode, getSecretKey());
    }
    return cipher;
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private SecretKey getSecretKey () throws Exception {
    KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
    keyStore.load(null);
    Key key = keyStore.getKey(keyAlias, null);
    if (key instanceof SecretKey) {
      return (SecretKey) key;
    }
    KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
      keyAlias,
      KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
      .setUserAuthenticationRequired(true);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      spec.setUserAuthenticationParameters(
        AUTHENTICATION_VALIDITY_SECONDS,
        KeyProperties.AUTH_BIOMETRIC_STRONG
      );
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      spec.setInvalidatedByBiometricEnrollment(true);
    }
    KeyGenerator generator =
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
    generator.init(spec.build());
    return generator.generateKey();
  }

  private void deleteKey () {
    try {
      KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
      keyStore.load(null);
      keyStore.deleteEntry(keyAlias);
    } catch (Exception e) {
      Log.w("Cannot delete biometry key", e);
    }
  }

  // Hex

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  private static String toHex (byte[] bytes) {
    char[] chars = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int value = bytes[i] & 0xff;
      chars[i * 2] = HEX_DIGITS[value >>> 4];
      chars[i * 2 + 1] = HEX_DIGITS[value & 0x0f];
    }
    return new String(chars);
  }

  private static @Nullable byte[] fromHex (@Nullable String hex) {
    if (hex == null || hex.isEmpty() || hex.length() % 2 != 0) {
      return null;
    }
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      int high = Character.digit(hex.charAt(i * 2), 16);
      int low = Character.digit(hex.charAt(i * 2 + 1), 16);
      if (high < 0 || low < 0) {
        return null;
      }
      bytes[i] = (byte) ((high << 4) | low);
    }
    return bytes;
  }
}
