use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use ruaccent_rs::pipeline::RuAccent;
use std::path::Path;
use std::sync::{Mutex, OnceLock};

static ACCENTOR: OnceLock<Mutex<Option<RuAccent>>> = OnceLock::new();

fn slot() -> &'static Mutex<Option<RuAccent>> {
    ACCENTOR.get_or_init(|| Mutex::new(None))
}

#[no_mangle]
pub extern "system" fn Java_ru_kost_ruvoice_StressOnnx_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    model_dir: JString,
    ort_library: JString,
) -> jboolean {
    let result = (|| -> Result<(), String> {
        let model_dir: String = env.get_string(&model_dir).map_err(|e| e.to_string())?.into();
        let ort_library: String = env.get_string(&ort_library).map_err(|e| e.to_string())?.into();
        ort::init_from(ort_library)
            .map_err(|e| e.to_string())?
            .commit();
        let model = RuAccent::load_with_omograph(Path::new(&model_dir), "turbo3.1")
            .map_err(|e| e.to_string())?;
        *slot().lock().map_err(|e| e.to_string())? = Some(model);
        Ok(())
    })();
    match result {
        Ok(()) => JNI_TRUE,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_kost_ruvoice_StressOnnx_nativeStress(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let original: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e.to_string());
            return std::ptr::null_mut();
        }
    };
    let result = match slot().lock() {
        Ok(mut guard) => match guard.as_mut() {
            Some(model) => model.process_all_with_skip(&original, r"(?i)[а-яё]*\+[а-яё]*"),
            None => original,
        },
        Err(_) => original,
    };
    match env.new_string(result) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", e.to_string());
            std::ptr::null_mut()
        }
    }
}
