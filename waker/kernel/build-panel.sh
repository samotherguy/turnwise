#!/bin/sh -e
# Builds the patched S6E3FA3 panel module against the phone's own kernel source and config. Run on the phone.
cd ~
[ -f linux.tar.gz ] || wget -q -O linux.tar.gz https://gitlab.com/msm8996-mainline/linux/-/archive/v6.3.1-msm8996/linux-v6.3.1-msm8996.tar.gz
[ -d linux-v6.3.1-msm8996 ] || tar xzf linux.tar.gz
cd linux-v6.3.1-msm8996
patch -p1 -N < ~/0001-s6e3fa3-wake-and-brightness.patch || true
cp ~/config-postmarketos-qcom-msm8996.aarch64 .config
make -s olddefconfig
echo "release: $(make -s kernelrelease)   $(date +%T)"
make -s -j4 modules_prepare
echo "prepared $(date +%T)"
make -s -j4 M=drivers/gpu/drm/panel modules
ls -la drivers/gpu/drm/panel/panel-samsung-s6e3fa3.ko
modinfo drivers/gpu/drm/panel/panel-samsung-s6e3fa3.ko | grep -E "vermagic"
modinfo /lib/modules/6.3.1-msm8996/kernel/drivers/gpu/drm/panel/panel-samsung-s6e3fa3.ko | grep vermagic
echo "BUILD DONE $(date +%T)"
