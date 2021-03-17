/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */

package xjunz.tool.werecord.impl;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteDatabaseHook;

import org.apaches.commons.codec.digest.DigestUtils;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import io.reactivex.CompletableObserver;
import io.reactivex.android.schedulers.AndroidSchedulers;
import xjunz.tool.werecord.App;
import xjunz.tool.werecord.BuildConfig;
import xjunz.tool.werecord.impl.model.account.User;
import xjunz.tool.werecord.util.IoUtils;
import xjunz.tool.werecord.util.RxJavaUtils;
import xjunz.tool.werecord.util.ShellUtils;
import xjunz.tool.werecord.util.Utils;

/**
 * An entity represents the fundamental functional environment of this application, which defines all kinds of constants,
 * initializes databases and creates {@link User} instances.
 * <p>
 * This entity is designed in single-instance mode. Please call {@link Environment#getInstance()} to obtain the instance.
 * Don't expect the instance returned is non-null, because the system would recycle this instance anytime when this app is
 * running in the background after home key is pressed.
 * <p>
 * In normal case, this instance's lifecycle is across the lifecycle of the application from {@link Environment#init(CompletableObserver)} is finished util
 * the user manually exits the app.
 */
public class Environment implements Serializable, LifecycleOwner {
    private final LifecycleRegistry mLifecycle;
    private static Environment sEnvironment;
    private String mVictimDataPath;
    private String mVictimSharedPrefsPath;
    private String mVictimMicroMsgPath;
    private final String separator = File.separator;
    private String mWorkerDatabaseDirPath;
    private String mImei;
    private List<User> mUserList;
    private String mAvatarBackupPath;
    private String mLastLoginUin;
    private String mAppFilesDir;
    private List<String> mUinList;
    private SQLiteDatabase mDatabaseOfCurUser;
    private User mCurrentUser;
    private DatabaseModifier mModifier;
    private static final String DEF_IMEI = "1234567890ABCDEF";

    public static final class InitializationException extends Exception {
        public int reasonCode;
        /**
         * 找不到KeyInfo
         */
        public static final int REASON_NO_KEY_INFO = 0;
        /**
         * 有KeyInfo但是里面没有IMEI
         */
        public static final int REASON_NULL_IMEI = 1;
        /**
         * 找不到UIN，既没有last login uin也没有uin set
         */
        public static final int REASON_NO_UIN = 2;
        /**
         * 有uin，但是找不到任何匹配的数据库文件
         */
        public static final int REASON_NO_DB_FILE_MATCHES_UIN = 3;

        private InitializationException(int reasonCode) {
            super("Initiate failed: " + reasonCode);
            this.reasonCode = reasonCode;
        }
    }

    /**
     * @return 当前工作数据库
     */
    @NonNull
    public SQLiteDatabase getWorkerDatabase() {
        if (!initialized()) {
            throw new IllegalStateException("Environment is not initialized successfully! Current state is " + getLifecycle().getCurrentState().toString());
        }
        return mDatabaseOfCurUser;
    }

    @NonNull
    public User getCurrentUser() {
        if (!initialized()) {
            throw new IllegalStateException("Environment is not initialized successfully! Current state is " + getLifecycle().getCurrentState().toString());
        }
        return mCurrentUser;
    }

    public String getVictimMicroMsgPath() {
        return mVictimMicroMsgPath;
    }

    public String getAvatarBackupPath() {
        return mAvatarBackupPath;
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    /**
     * initiate the environment
     *
     * @param observer the callback
     */
    public void init(CompletableObserver observer) {
        RxJavaUtils.complete(() -> {
            //create backup dirs
            mAppFilesDir = App.getContext().getFilesDir().getPath();
            mWorkerDatabaseDirPath = mAppFilesDir + separator + DigestUtils.md5Hex("database_backup");
            File dbBackupDir = new File(mWorkerDatabaseDirPath);
            if (!dbBackupDir.exists() && !dbBackupDir.mkdir())
                throw new RuntimeException("Failed to create db backup dir");
            mAvatarBackupPath = mAppFilesDir + separator + DigestUtils.md5Hex("avatar_backup");
            File avatarBackupDir = new File(mAvatarBackupPath);
            if (!avatarBackupDir.exists() && !avatarBackupDir.mkdir())
                throw new RuntimeException("Failed to create avatar backup dir");
            PackageManager packageManager = App.getContext().getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo("com.tencent.mm", 0);
            mVictimDataPath = packageInfo.applicationInfo.dataDir;
            mVictimMicroMsgPath = mVictimDataPath + separator + "MicroMsg";
            mVictimSharedPrefsPath = mVictimDataPath + separator + "shared_prefs";
            readImei();
            loadUins();
            //initUsers();
            copyWorkerDatabase(mCurrentUser);
            tryOpenDatabaseOf(mCurrentUser, mImei);
            fulfillCurrentUser();
            setCurrentStateInMainThread(Lifecycle.State.STARTED);
        }).subscribe(observer);
    }

    public List<User> getUserList() {
        return mUserList;
    }

    private void initUsers() {
        String lastUsedUin = App.config().lastUsedUin.getValue();
        mUserList = new ArrayList<>();
        for (String uin : mUinList) {
            User user = new User(uin);
            //如果是上次登录
            if (Objects.equals(mLastLoginUin, uin)) {
                user.isLastLogin = true;
                //并且没有上次使用记录
                if (lastUsedUin == null) {
                    //默认使用上次登录UIN初始化应用
                    mCurrentUser = user;
                    mCurrentUser.isCurrentUsed = true;
                    App.config().lastUsedUin.setValue(uin);
                }
            }
            if (lastUsedUin != null) {
                if (Objects.equals(lastUsedUin, uin)) {
                    mCurrentUser = user;
                    mCurrentUser.isCurrentUsed = true;
                }
            }
            mUserList.add(user);
        }
        //no uin in set matches last login uin
        if (mCurrentUser == null) {
            //invalidate user list
            mUinList.clear();
            mUserList.clear();
            mCurrentUser = new User(mLastLoginUin);
        } else {
            try {
                String out = ShellUtils.cat(mVictimSharedPrefsPath + separator + "com.tencent.mm_preferences_account_switch.xml");
                // /data/user/0/com.tencent.mm/shared_prefs/com.tencent.mm_preferences_account_switch.xml
                List<String> ids = Utils.extract(out, "string>(.+?)<");
                if (ids.size() == mUinList.size()) {
                    for (int i = 0; i < ids.size(); i++) {
                        User user = mUserList.get(i);
                        if (user.id == null) {
                            user.id = ids.get(i);
                        }
                    }
                }
            } catch (ShellUtils.ShellException e) {
                //no such file, don't throw exception
            }
        }
    }

    private void fulfillCurrentUser() {
        try (Cursor cursor = mDatabaseOfCurUser.rawQuery("select id,value from userinfo where id in(2,4,6,42) ", null)) {
            while (cursor.moveToNext()) {
                int id = cursor.getInt(0);
                String value = cursor.getString(1);
                switch (id) {
                    case 2:
                        mCurrentUser.id = value;
                        break;
                    case 4:
                        mCurrentUser.nickname = value;
                        break;
                    case 6:
                        mCurrentUser.phoneNum = value;
                        break;
                    case 42:
                        mCurrentUser.alias = value;
                        break;
                }
            }
        }
    }

    public boolean initialized() {
        return mLifecycle.getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    private void setCurrentStateInMainThread(Lifecycle.State state) {
        AndroidSchedulers.mainThread().scheduleDirect(() -> mLifecycle.setCurrentState(state));
    }

    public void addLifecycleObserver(LifecycleObserver observer) {
        AndroidSchedulers.mainThread().scheduleDirect(() -> mLifecycle.addObserver(observer));
    }

    private Environment() {
        mLifecycle = new LifecycleRegistry(this);
        setCurrentStateInMainThread(Lifecycle.State.CREATED);
    }

    public static Environment create() {
        sEnvironment = new Environment();
        return sEnvironment;
    }

    public static Environment getInstance() {
        return sEnvironment;
    }

    private void copyWorkerDatabase(@NotNull User user) throws IOException, ShellUtils.ShellException {
        user.workerDatabaseFilePath = mWorkerDatabaseDirPath + File.separator + DigestUtils.md5Hex(user.uin);
        ShellUtils.cp2data(user.originalDatabaseFilePath, user.workerDatabaseFilePath, true);
    }

    /**
     * 备份原数据库
     */
    public void backupOriginDatabaseOf(@NotNull User user) throws IOException, ShellUtils.ShellException {
        user.backupDatabaseFilePath = mWorkerDatabaseDirPath + File.separator + DigestUtils.md5Hex("backup");
        ShellUtils.cp2data(user.originalDatabaseFilePath, user.backupDatabaseFilePath, true);
    }


    private void loadUins() throws ShellUtils.ShellException, InitializationException {
        String lastLoginUin = null;
        ///data/user/0/com.tencent.mm/shared_prefs/com.tencent.mm_preferences.xml
        String lastLoginUinFilePath = mVictimSharedPrefsPath + separator + "com.tencent.mm_preferences.xml";
        if (ShellUtils.isFileExists(lastLoginUinFilePath)) {
            lastLoginUin = Utils.extractFirst(ShellUtils.cat(lastLoginUinFilePath), "last_login_uin\">(.+?)<");
        }
        ///data/user/0/com.tencent.mm/shared_prefs/app_brand_global_sp.xml
        String uinSetFilePath = mVictimSharedPrefsPath + separator + "app_brand_global_sp.xml";
        if (ShellUtils.isFileExists(uinSetFilePath)) {
            mUinList = Utils.extract(ShellUtils.cat(uinSetFilePath), ">(.+?)<");
        }
        User tempUser = new User(lastLoginUin);
        if (lastLoginUin != null && ShellUtils.isFileExists(tempUser.originalDatabaseFilePath)) {
            mCurrentUser = tempUser;
            mCurrentUser.isLastLogin = true;
        } else {
            if (mUinList == null || mUinList.isEmpty()) {
                throw new InitializationException(InitializationException.REASON_NO_UIN);
            } else {
                for (String uin : mUinList) {
                    User user = new User(uin);
                    if (ShellUtils.isFileExists(user.originalDatabaseFilePath)) {
                        mCurrentUser = user;
                        return;
                    }
                    throw new InitializationException(InitializationException.REASON_NO_DB_FILE_MATCHES_UIN);
                }
            }
        }
    }

    /**
     * Use {@link Environment#readImei()} instead.
     */
    @SuppressWarnings("unchecked")
    @Deprecated
    private void initImei() {
        //fetch from SP first
        mImei = App.getSharedPrefsManager().getImei();
        if (mImei != null) {
            return;
        }
        //otherwise get from file
        String temp = mAppFilesDir + separator + "temp.cfg";
        File tempFile = new File(temp);
        try {
            ShellUtils.cp2data(mVictimMicroMsgPath + File.separator + "CompatibleInfo.cfg", temp, false);
            FileInputStream fis = new FileInputStream(tempFile);
            ObjectInputStream ois = new ObjectInputStream(fis);
            HashMap<Integer, String> map = (HashMap<Integer, String>) ois.readObject();
            //258
            mImei = map.get(0x102);
            fis.close();
            ois.close();
        } catch (IOException | ShellUtils.ShellException | ClassNotFoundException e) {
            e.printStackTrace();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }

        if (mImei == null) {
            //def imei
            mImei = "1234567890ABCDEF";
        }
    }

    private void readImei() throws NoSuchPaddingException, NoSuchAlgorithmException, IOException, ShellUtils.ShellException, InvalidKeyException, InitializationException {
        App.SharedPrefsManager spm = App.getSharedPrefsManager();
        //先从缓存读取
        if (spm.isAppIntroDone() && (mImei = spm.getImei()) != null) {
            return;
        }
        //否则从文件读取
        String keyInfoPath = mVictimDataPath + separator + "files" + separator + "KeyInfo.bin";
        if (!ShellUtils.isFileExists(keyInfoPath)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mImei = DEF_IMEI;
            } else {
                throw new InitializationException(InitializationException.REASON_NO_KEY_INFO);
            }
        }
        SecretKeySpec secretKeySpec = new SecretKeySpec(new byte[]{95, 119, 69, 99, 72, 65, 84, 95}, "RC4");
        Cipher cipher = Cipher.getInstance("RC4");
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec);
        File tempFile = File.createTempFile("init_info", null);
        ShellUtils.cp(keyInfoPath, tempFile.getPath());
        BufferedReader reader = new BufferedReader(new InputStreamReader(new CipherInputStream(new FileInputStream(tempFile), cipher)));
        mImei = reader.readLine();
        reader.close();
        IoUtils.deleteFile(tempFile);
        if (mImei == null) {
            throw new InitializationException(InitializationException.REASON_NULL_IMEI);
        }
    }

    private static final SQLiteDatabaseHook COMPATIBILITY_HOOK = new SQLiteDatabaseHook() {
        @Override
        public void preKey(SQLiteDatabase database) {

        }

        @Override
        public void postKey(@NotNull SQLiteDatabase database) {
            //微信的CipherDB版本还是1，可以用以下指令打开，但是实测，用这个指令打开，修改数据库不会同步到本地文件
            //database.rawExecSQL("PRAGMA cipher_compatibility = 1;");
            //也可以用下面这条指令打开，但是速度较慢
            //migrate this database to latest version
            database.rawExecSQL("PRAGMA cipher_migrate;");
        }
    };

    private void tryOpenDatabaseOf(@NonNull User user, @NonNull String imei) {
        String possibleKey = DigestUtils.md5Hex(imei + user.uin).substring(0, 7).toLowerCase();
        int flag = App.config().isEditModeEnabled() ? SQLiteDatabase.OPEN_READWRITE : SQLiteDatabase.OPEN_READONLY;
        mDatabaseOfCurUser = SQLiteDatabase.openDatabase(user.workerDatabaseFilePath, possibleKey, null, flag, COMPATIBILITY_HOOK);
        user.databasePassword = possibleKey;
        App.getSharedPrefsManager().putImei(imei);
    }

    public DatabaseModifier modifyDatabase() {
        synchronized (DatabaseModifier.class) {
            if (mModifier == null) {
                mModifier = new DatabaseModifier(this);
            }
            return mModifier;
        }
    }

    public void reopenDatabase(int mode) {
        mDatabaseOfCurUser.close();
        mDatabaseOfCurUser = SQLiteDatabase.openDatabase(mCurrentUser.workerDatabaseFilePath, mCurrentUser.databasePassword, null, mode, COMPATIBILITY_HOOK);
    }

    public void purge() {
        if (mDatabaseOfCurUser != null) {
            mDatabaseOfCurUser.close();
        }
        if (mCurrentUser != null) {
            mCurrentUser.deleteWorkerDatabase();
        }
        setCurrentStateInMainThread(Lifecycle.State.DESTROYED);
        sEnvironment = null;
    }

    @NotNull
    public static String getBasicEnvInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Env status: ").append(sEnvironment == null ? "NOT CREATED" : sEnvironment.getLifecycle().getCurrentState()).append("\n");
        sb.append(infoBlockHeader("B")).append("\n").append(getBasicHardwareInfo()).append("\n")
                .append(infoBlockHeader("A")).append("\n").append(getAppVersionInfo()).append("\n")
                .append(infoBlockHeader("V")).append("\n").append(getVictimVersionInfo());
        if (sEnvironment != null && sEnvironment.initialized()) {
            sb.append("\n").append(infoBlockHeader("U")).append("\n").append(sEnvironment.getBasicUserInfo());
        }
        return sb.toString();
    }

    @NotNull
    @Contract(pure = true)
    public static String infoBlockHeader(String infoName) {
        return String.format("=====%s=====", infoName);
    }

    @NotNull
    private static String getBasicHardwareInfo() {
        return "release: " + Build.VERSION.RELEASE + "\nSDK: " + Build.VERSION.SDK_INT
                + "\nbrand: " + Build.BRAND + "\nmodel: " + Build.MODEL + "\nCPU_ABI: " + Arrays.toString(Build.SUPPORTED_ABIS);
    }

    @NonNull
    @Contract(pure = true)
    private static String getAppVersionInfo() {
        return "app_version_name: " + BuildConfig.VERSION_NAME + "\napp_version_code: " + BuildConfig.VERSION_CODE;
    }

    public String getBasicUserInfo() {
        return "pragmaKeyed: " + (mCurrentUser.databasePassword != null)
                + "\ndirPath: " + mCurrentUser.dirPath + "\nworkerDbPath: " + mCurrentUser.workerDatabaseFilePath;
    }

    @NotNull
    private static String getVictimVersionInfo() {
        try {
            PackageInfo packageInfo = App.getContext().getPackageManager().getPackageInfo("com.tencent.mm", 0);
            return "victim_version_code: " + packageInfo.versionCode + "\nvictim_version_name: " + packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return "victim_version_code: <error> \nvictim_version_name: <error>";
    }

    @NotNull
    private static String getProcessInfo() {
        return "pid:" + Process.myPid()
                + "\n" + "uid:" + Process.myUid();
    }
}
