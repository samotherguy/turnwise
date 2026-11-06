# Waker: a OnePlus 3 as the always-on node that wakes the Turnwise box

Built 2026-10-31. The spare OnePlus 3 (A3003, Snapdragon 820, 6 GB RAM, 64 GB UFS) runs postmarketOS v25.12
(the project was renamed Nura in September 2026; same thing) as a headless appliance on the home Wi-Fi and the
tailnet. It wakes the GPU box ("com") and the LLM box ("com-2") over Wake-on-LAN, can shut com down, and can
proxy Turnwise so a browser never notices the box was off. The Turnwise Android app talks to it directly.

This file is the full build log with every dead end, so the next install takes an hour, not a day.

## What runs on the phone

| Thing | Where | Notes |
|---|---|---|
| waker service | `/usr/local/bin/waker.py`, `/etc/waker.conf`, `/etc/init.d/waker` (OpenRC, `default` runlevel) | `GET /` mobile page with Wake/Off buttons, `GET /status`, `POST /wake`, `POST /wake/llm`, `POST /off`, `POST /off/llm`; port 8490 |
| wake-on-demand proxy | same process, `127.0.0.1:8447` | forwards everything to `UPSTREAM` (the box's Turnwise HTTPS URL), wakes the box first and holds the request until `/api/me` answers (`WAKE_WAIT` 150 s) |
| Tailscale Serve | `https://waker.<tailnet>.ts.net:8446` → proxy, `:8491` → waker page/API | persisted in tailscaled state (`tailscale serve --bg --https=PORT http://127.0.0.1:PORT`) |
| firewall | `/etc/nftables.d/50-waker.nft` | accepts 41641/udp, anything from `tailscale0`, 8490/tcp, 22/tcp |
| root | `/etc/doas.d/abhyuday.conf`: `permit nopass abhyuday` | `sudo` is a doas shim; passwordless since the reset below |
| poweroff key | `/root/.ssh/waker_key` | on the box its public key is pinned to `/usr/local/bin/waker-poweroff` (see `box_poweroff_setup.sh`) |
| hosts | `/etc/hosts`: `100.118.250.80 <box>.<tailnet>.ts.net` | MagicDNS does not resolve on the phone; Tailscale Serve needs the right SNI, so an IP would get a TLS alert |

Addresses: Wi-Fi `192.168.100.16` (SSID `HUAWEI-8PnH`, same /24 as the box; the `-6G` SSID is 6 GHz and unusable),
Tailscale `100.101.41.32` / `waker.<tailnet>.ts.net`. Box: `192.168.100.2`, MAC `00:10:f3:a1:5f:7a`,
Tailscale `100.118.250.80`. LLM box MAC `00:10:f3:a1:5f:ad`, Tailscale `100.73.147.59`.

Files in this directory are the sources: `waker.py`, `waker.conf` (template), `waker.initd`, `50-waker.nft`,
`install_on_phone.sh` (idempotent, run as root on the phone), `box_poweroff_setup.sh` (run on the box).
Boot images live on the box in `~/waker-phone/`: `lk2nd.img`, `boot.img`, `boot-debug.img`, `combined-*.img`.

## Why these choices

- **postmarketOS, not LineageOS.** We want a Linux server (OpenRC, Python, SSH, Tailscale, nftables), not Android.
- **v25.12, not edge.** The edge kernel (6.19.5) does not boot on this chip; 6.3.1 from v25.12 does.
- **lk2nd.** The stock OnePlus bootloader refuses mainline boot images (it bounces straight back to fastboot).
  lk2nd 23.1 (`lk2nd-msm8996.img` from github.com/msm8916-mainline/lk2nd releases) sits at offset 0 of the
  `boot` partition and boots the real boot image stored at offset 512 KiB in the same partition.
- **Console UI.** No graphical interface: a one-key menu and an on-screen keyboard on the framebuffer console (see below). Press the power button to light the screen.
- **Direct-first in the app.** The Android app talks to the box itself and only uses the waker when the box does
  not answer. Routing everything through the phone would buffer uploads in its RAM and add a hop to every call.

## Hardware caveats

Works under mainline: Wi-Fi (ath10k QCA6174 over PCIe), USB networking, screen and touch, buttons, battery
and charging, storage, CPU. Not working: GPU, audio DSP and video decoding (their proprietary firmware was not
copied over; irrelevant headless), cameras, modem, fingerprint, NFC, most sensors. Bluetooth untested.
The battery does **not** charge from the box's USB port; it needs its own wall charger. The GPU (Adreno 530)
is not usable for ML work: no CUDA, no usable OpenCL/Vulkan compute, Hexagon DSP unreachable from mainline.

## Building the image (on the box)

1. pmbootstrap 3.12 is not on PyPI and Ubuntu's 2.1.0 is too old. Clone it into a venv:
   ```
   python3 -m venv ~/.local/opt/pmb-venv && ~/.local/opt/pmb-venv/bin/pip install git+https://gitlab.postmarketos.org/postmarketOS/pmbootstrap.git
   ```
   (This was done from a scratch dir that a reboot wiped; put it somewhere durable this time.)
2. `pmbootstrap init`: device `oneplus-oneplus3`, UI `console`, user `abhyuday`, hostname `waker`, extra packages
   `tailscale,python3`, SSH key from the box. The prompt order changes between versions; it is simpler to edit
   `~/.config/pmbootstrap_v3.cfg` directly (configparser format).
3. The pmaports checkout lives in `~/.local/var/pmbootstrap/cache_git/pmaports`. Check out the release branch:
   `git checkout v25.12` (`pmbootstrap config channel` rejects it; after switching, `pmbootstrap zap` because
   the chroots were built for edge).
4. The device package is archived upstream ("unmaintained") and not in the binary repo, so it is built locally.
   Patch `device/testing/device-oneplus-oneplus3/APKBUILD` and `deviceinfo`:
   - `maintainer="Local Build <local@build.invalid>"` (abuild refuses to build without a valid maintainer line),
   - bump `pkgrel` (was 10),
   - `deviceinfo_flash_sparse="false"` (`android-tools-img2simg` is missing on v25.12),
   - `deviceinfo_kernel_cmdline="pcie_aspm=off pci=nomsi"` (Wi-Fi; see below),
   - recompute the `sha512sums` for `deviceinfo` (`python3 -c "import hashlib;print(hashlib.sha512(open('deviceinfo','rb').read()).hexdigest())"`),
   - if abuild says "found in multiple aports subfolders", delete the archived copy of the package.
   The firmware package `soc-qcom-msm8996-nonfree-firmware` exists on v25.12 but not on edge (there it would have
   to be swapped for `linux-firmware-qcom`).
   **Gotcha:** the official binary package `device-oneplus-oneplus3-1-r11` outranks a local `r10`, so the cmdline
   from the local deviceinfo never reached the image. Either use a higher pkgrel than upstream or fix it on the
   phone afterwards (step "Wi-Fi" below).
5. Build with the UFS 4Kn sector size and a chosen password (write it down somewhere durable):
   ```
   pmbootstrap install --password '<pw>' --sector-size 4096
   pmbootstrap export      # gives boot.img and the rootfs image (oneplus-oneplus3.img, 1.96 GB raw)
   ```
   If apk says "Unable to lock database", a stale `apk.static` is holding the chroot: kill it by PID
   (a broad `pkill` pattern killed our own shell twice).

The box needs `fastboot` (`apt install fastboot`) and a udev rule for vendors `2a70` and `18d1`
(`/etc/udev/rules.d/51-android.rules`). `qemu-user-static`, `binfmt-support` and `kpartx` are installed for pmbootstrap.

## Flashing

Boot partition layout (64 MiB): `[0, 512 KiB)` lk2nd, `[512 KiB, …)` the OS `boot.img`. lk2nd contains a false
`ANDROID!` string at byte 312188; ignore it.

1. Stock fastboot (phone off → hold Volume Up + Power, or `adb reboot bootloader`; the device is unlocked):
   `fastboot flash boot lk2nd.img`. Stock aboot accepts lk2nd because it is packaged as an Android boot image.
2. Reboot holding **Volume Down** to get lk2nd's own fastboot (lk2nd menu on screen; `fastboot getvar product`
   says `msm8996` for both bootloaders, so the screen is the tell).
3. **Do not `fastboot flash boot boot.img` through lk2nd on this device.** It wrote at offset 0 and overwrote
   lk2nd (the stock bootloader then answers fastboot, reporting `partition-size:boot 0x4000000`). Instead flash
   one combined image from **stock** fastboot:
   ```
   python3 - <<'PY'
   lk=open('lk2nd.img','rb').read(); b=open('boot.img','rb').read()
   open('combined.img','wb').write(lk.ljust(524288,b'\0')+b)
   PY
   fastboot flash boot combined.img
   ```
   (`~/waker-phone/combined-normal.img` is exactly this.) `fastboot boot boot.img` through lk2nd also fails with
   `dtb not found` even though the same image boots fine from the partition (known lk2nd quirk).
4. The rootfs goes to the `userdata` partition (`/dev/sda15` on the phone, 4K sectors). `fastboot flash userdata`
   through lk2nd dropped the USB link part-way through a 2 GB write every time. What worked: boot the combined
   image, let the initramfs fail to find a root partition (it then exposes a telnet shell on the USB network,
   phone `172.16.42.1`, box `172.16.42.2`, interface `enx…`), and stream the image:
   ```
   # phone, via: printf '…' | nc 172.16.42.1 23
   nc -l -p 9000 > /dev/disk/by-partlabel/userdata
   # box
   nc -w 3 172.16.42.1 9000 < oneplus-oneplus3.img      # ~127 s
   ```
   Then reboot. "Waiting for root partition…" on screen for a while is normal; so is "Starting…".

## First boot fixes

- **Wi-Fi.** ath10k crashes unless the kernel runs with `pcie_aspm=off pci=nomsi`. On the phone, append
  `deviceinfo_kernel_cmdline="pcie_aspm=off pci=nomsi"` to `/etc/deviceinfo`, run `mkinitfs`, and write the new
  `/boot/boot.img` into the boot slot: `dd if=/boot/boot.img of=/dev/disk/by-partlabel/boot bs=524288 seek=1 conv=notrunc,fsync`.
  Check with `cat /proc/cmdline` after a reboot. `wlan0` appears about 2 minutes after boot; join with
  `nmcli dev wifi connect HUAWEI-8PnH password …`. Tailscale needs Wi-Fi, so it also takes ~3 minutes after boot.
- **Root.** `sudo` is a doas shim that needs a tty: `ssh -tt phone 'sudo -i'` and pipe the password. Now moot:
  `/etc/doas.d/abhyuday.conf` has `permit nopass abhyuday`.
- **OpenRC, not systemd** on v25.12. The Tailscale service is called `tailscale`. `rc-update add waker default`.
- **Firewall.** nftables is on by default and drops everything new; `50-waker.nft` is included from `nftables.nft`.
- **Tailscale.** `tailscale up --hostname waker`, approve in the admin console. If devices cannot reach each
  other even though `tailscale ping` works, check the ACL: after the GitHub handle rename the policy still said
  `Abhyuday98@github` while every device is owned by `samotherguy@github`, so the broad grant matched nothing
  and the waker's inbound packet filter was empty. The same rename made Turnwise's `OWNER_LOGIN` stale
  (`~/.config/audio-notes/env`), silently treating every device as a guest with its own notes folder.
- **Phone-side setup** is `install_on_phone.sh` (copy `waker.py` to `/home/abhyuday/` first). It installs the
  service, the hosts entry, the proxy config and the `tailscale serve` rule.
- **Box-side setup** is `box_poweroff_setup.sh`: a forced-command script that only accepts `ping` or `poweroff`,
  a `NOPASSWD` sudoers drop-in for `systemctl poweroff`, and the phone's key pinned to that script. Test with
  `ssh -i /root/.ssh/waker_key <box> ping` from the phone → `pong`. WoL on the box's NIC is armed (`ethtool enp2s0`
  shows `Wake-on: g`) and survives a `systemctl poweroff`; the magic packet is 102 bytes to `192.168.100.255:9`.

## Recovering root if the password is lost (done once, 2026-10-31)

The install password lived only in a scratch file that a reboot wiped. Recovery without reflashing:
1. USB to the box, reboot holding Volume Down → lk2nd fastboot.
2. `fastboot flash boot ~/waker-phone/combined-debug.img` (lk2nd + boot.img with `pmos.debug-shell` appended to
   the cmdline), `fastboot reboot`. Telnet opens on `172.16.42.1:23` within ~20 s.
3. In the debug shell: `kpartx -afs /dev/sda15`, `mount /dev/mapper/sda15p2 /mnt`, `mount /dev/mapper/sda15p1 /mnt/boot`,
   `echo 'abhyuday:NEW' | chroot /mnt /usr/sbin/chpasswd`, write `/mnt/etc/doas.d/abhyuday.conf`, then put the
   normal image back **before** continuing: `dd if=/mnt/boot/boot.img of=/dev/sde18 bs=524288 seek=1 conv=notrunc,fsync`
   (`sde18` is `boot`; check with `blkid`). `umount`, `kpartx -d /dev/sda15`, `pmos_continue_boot`.
   That boot still shows the debug flag in `/proc/cmdline`; the next one is clean.

## The screen: a console UI, not a compositor

The phone has a small UI so it is not a black slab: press the power button and the panel shows a one-key menu
with com and com-2 (status, wake, shut down), the battery and both addresses, a shell (`t`), and an on-screen
keyboard across the bottom third. The screen goes dark again after 90 s. Everything is in `console/`:

- `screen.py` (root, OpenRC service `waker-screen`): power key toggles the backlight (never the panel), idle
  timeout, and the keyboard: drawn straight into `/dev/fb0` with Pillow, touches hit-tested and typed into the
  console through `/dev/uinput`. It shrinks tty1 to the rows above the keyboard.
- `waker-menu`: the menu, started by `~/.profile` on tty1; `waker-login` makes getty log the user in there.
- `install.sh`: packages (`py3-pillow`, `font-terminus`, busybox has `setfont`), the service, the inittab line.

Why not Sxmo or Phosh: they were tried on 2026-11-02 and both attempts hung the SoC within seconds of
starting sway, with and without software rendering. The Adreno 530 never initialises (`a530_zap.mbn`
fails with EIO, like every other TrustZone-signed blob: the phone's firmware partitions do not match the blobs
postmarketOS ships), and the display driver's modeset path hangs the whole SoC once a DRM master takes over.
The framebuffer console was on screen during boot and has never hung, so the UI stays on it. If a
compositor is ever started again, have `ssh` ready over USB (`172.16.42.1`) to `rc-update del tinydm default`
on the way back up, or the phone will hang at every boot.

## Turnwise Android app

`Waker.kt` in the app repo (`~/personal_projects/repos/turnwise-listener`). Settings → Power panel shows com and
com-2 with Wake/Off; `wakerUrl` defaults to `https://waker.<tailnet>.ts.net:8491`. Rules: Listen is the home
tab and works offline; entering Notes or Ask, opening a note, or sending a recording wakes the box if it does
not answer; nothing else ever wakes it, screens just show an "off" banner with a Wake button. The Turnwise
address stays the box's own URL. Build: `JAVA_HOME=~/.local/opt/jdk17 ANDROID_HOME=~/Android/Sdk ~/.local/opt/gradle-8.9/bin/gradle assembleFullDebug` (`assembleDemoDebug` for the demo flavour);
deliver by copying the APK to `audio-notes/static/Turnwise.apk` (served at the Turnwise URL) or `tailscale file cp`.

## Still open

- com-2 Off: run `box_poweroff_setup.sh` on the LLM box and set `LLM_SSH=user@100.73.147.59` in `/etc/waker.conf`.
- The proxy buffers request bodies in RAM and ignores chunked trailers; fine for this use.
- The Sxmo packages are still installed (inert, `tinydm` is out of the runlevel); `apk del postmarketos-ui-sxmo-de-sway` frees ~400 MB.
