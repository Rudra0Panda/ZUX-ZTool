![bc5c7a34fee614e08839b511a5840873.jpg](https://i.loli.net/2020/01/30/fOFvI2o9KXqEkJr.jpg)
# Magisk Module Template

`This is the basic structure of a Magisk module`
```
module.zip
│
├── META-INF
│   └── com
│       └── google
│           └── android
│               ├── update-binary      <--- Obtain this file by downloading module_installer.sh
│               └── updater-script     <--- Should only contain the string "#MAGISK"
│
├── customize.sh                       <--- Contains "SKIPUNZIP=0". Change 0 to 1 if permission changes are needed
│                                           Sourced and executed by update-binary
├── module.prop
├── ...  /* Remaining module files */
|
```
**Except for the essential files listed above, other unnecessary files can be removed.**

**Refer to the [Magisk Module Template](https://github.com/HANA-CI-Build-Project/magisk-module-template).**

For more information regarding modules and repositories, please visit the [Magisk Official Documentation](https://topjohnwu.github.io/Magisk/guides.html).
