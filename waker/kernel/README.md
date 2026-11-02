# Panel driver fix for the OnePlus 3 (postmarketOS kernel 6.3.1-msm8996): work in progress

**Status (2026-11-03): not working yet.** With this patch the wake still fails and the sleep-in starts failing
too: the msm DSI host is powered from `mode_set` (`dsi_mgr_bridge_power_on`), which the fbdev blank/unblank
path does not run, so the host is off whenever the panel sends its commands. Next step is on the host side
(`drivers/gpu/drm/msm/dsi/dsi_manager.c`): power the host in `pre_enable` when it is not already on. The phone
currently runs the stock module (backup in `~/panel-stock.ko`), blanks the panel at boot and keeps it asleep.

Stock, the Samsung S6E3FA3 panel can be put to sleep but never wakes ("Failed to exit sleep mode: -22"), and
its brightness control does nothing. `0001-s6e3fa3-wake-and-brightness.patch` fixes both in
`drivers/gpu/drm/panel/panel-samsung-s6e3fa3.c`:

- `prepare_prev_first`: the DSI host is powered before the panel's wake-up commands are sent (the host returns
  `-EINVAL` to any command while off). The same one-line fix most Qualcomm phone panels got in later kernels.
- The init sequence sends `WRCTRLD 0x53 = 0x20`, which enables the panel's brightness control; without it the
  `0x51` brightness writes are ignored. Brightness is written as one byte, which this panel expects.

Only the module is rebuilt, natively on the phone (no cross compiler on the box, and the pmaports branch wants a
newer pmbootstrap than Ubuntu ships): `build-panel.sh` fetches the kernel source at the tag the package was built
from, applies the patch, prepares the tree with the stock config and builds `drivers/gpu/drm/panel`. The module's
vermagic matches the running kernel because the config and LOCALVERSION are the stock ones. Install:

```sh
doas cp /lib/modules/6.3.1-msm8996/kernel/drivers/gpu/drm/panel/panel-samsung-s6e3fa3.ko ~/panel-stock.ko
doas cp ~/linux-v6.3.1-msm8996/drivers/gpu/drm/panel/panel-samsung-s6e3fa3.ko /lib/modules/6.3.1-msm8996/kernel/drivers/gpu/drm/panel/
doas depmod -a && doas reboot
```

A kernel package upgrade will overwrite the module; rerun the install step after one (or carry the patch in
a local pmaports build).
