# 起動後の復帰

## 0.6.1: unlock receiver解除の冪等化

実機でUSER_UNLOCKED直後にCrashGuardのreceiver解除がIllegalArgumentExceptionとなり、SystemUIとホームが終了した履歴を検出。解除済みreceiverの二重解除を非致命的として扱い、guard status publishを継続する。クラッシュ停止条件や保存内容は変更しない。
