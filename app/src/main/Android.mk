LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := permissions_com.stevesoltys.backup.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/permissions
LOCAL_SRC_FILES := $(LOCAL_MODULE)
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := whitelist_com.stevesoltys.backup.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/sysconfig
LOCAL_SRC_FILES := $(LOCAL_MODULE)
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := Backup
LOCAL_PACKAGE_NAME := Backup
LOCAL_MODULE_TAGS := optional
LOCAL_REQUIRED_MODULES := permissions_com.stevesoltys.backup.xml whitelist_com.stevesoltys.backup.xml
LOCAL_PRIVILEGED_MODULE := true
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform

backup_root  := $(LOCAL_PATH)/../../../
backup_dir   := app
backup_out   := $(TARGET_COMMON_OUT_ROOT)/obj/APPS/$(LOCAL_MODULE)_intermediates
backup_build := $(backup_root)/$(backup_dir)/build
backup_apk   := $(backup_build)/outputs/apk/release/app-release-unsigned.apk

$(backup_apk):
	echo $(backup_root) >> /tmp/backup.txt
	echo $(backup_dir) >> /tmp/backup.txt
	echo $(backup_out) >> /tmp/backup.txt
	echo $(backup_build) >> /tmp/backup.txt
	echo $(backup_apk) >> /tmp/backup.txt
	rm -Rf $(backup_build)
	mkdir -p $(backup_out)
	ln -s $(backup_out) $(backup_build)
	echo "sdk.dir=$(ANDROID_HOME)" > $(backup_root)/local.properties
	cd $(backup_root) && JAVA_TOOL_OPTIONS="$(JAVA_TOOL_OPTIONS) -Dfile.encoding=UTF8" ../gradlew assembleRelease

LOCAL_SRC_FILES := $(backup_apk)
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
include $(BUILD_PREBUILT)
