

# FAQ - Frequently Asked Questions

## How do I prevent Singularity/Apptainer mount notification on Ubuntu?

When running jobs using singularity the mount of the loop device trigger the display of an icon on the Ubuntu Desktop. To prevent this behaviour you can run:
```bash
echo 'SUBSYSTEM=="block", KERNEL=="loop*", ENV{UDISKS_IGNORE}="1"' | sudo tee /etc/udev/rules.d/99-hide-loop.rules > /dev/null && sudo udevadm control --reload-rules
```

