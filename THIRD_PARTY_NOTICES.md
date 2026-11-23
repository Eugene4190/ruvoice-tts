# Third-party notices

## StressONNX / RUAccent

The Android integration uses the RUAccent-derived pipeline from [TigreGotico/stressonnx](https://github.com/TigreGotico/stressonnx), licensed Apache-2.0. RUAccent itself and its model assets are attributed to Den4ikAI and licensed MIT. Model weights are downloaded on first launch from the public Hugging Face repository [TigreGotico/stressonnx-models](https://huggingface.co/TigreGotico/stressonnx-models) at the pinned revision `cb1594ddf81928e678a3a7ee7a22415e6e3a218f`. The Android JNI adapter uses [alksef/ruaccent-rs](https://github.com/alksef/ruaccent-rs), pinned to commit `4dd4b2f49d2250ee17448484d040180e042f9cee`; the port is MIT-licensed.

## ONNX Runtime

The Android app uses Microsoft ONNX Runtime Android 1.32.0, MIT License.

## Additional Russian voices

The optional Russian voice pack is distributed by the upstream RuVoice project under CC BY-NC-SA 4.0. It is downloaded from the official release asset `ruvoice-pack-ru_voices.zip` and installed into the app's private files directory. Non-commercial use only; the upstream pack includes recording attributions.
