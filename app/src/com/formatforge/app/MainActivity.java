package com.formatforge.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private ScrollView scroll;
    private TextView fileName, fileMeta, targetTitle, targetSub;
    private TextView settingsPlaceholder, btnPick, btnConvert, btnOpen, btnShare, btnNew;
    private LinearLayout cardFile, cardTarget, cardSettings, settingsContainer, resultCard;
    private TextView resultName, resultMeta;
    private TextView sizeEstimate;

    private SourceFile source;
    private SizeEstimator.Info sourceInfo;
    private Formats.FormatSpec target;
    private List<Formats.FormatSpec> targets = new ArrayList<Formats.FormatSpec>();
    private Map<String, Object> values = new LinkedHashMap<String, Object>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean converting;
    private List<Exporter.Saved> lastSaved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Util.init(getApplicationContext());
        setContentView(R.layout.activity_main);

        scroll = (ScrollView) findViewById(R.id.scroll);
        cardFile = (LinearLayout) findViewById(R.id.cardFile);
        cardTarget = (LinearLayout) findViewById(R.id.cardTarget);
        cardSettings = (LinearLayout) findViewById(R.id.cardSettings);
        fileName = (TextView) findViewById(R.id.fileName);
        fileMeta = (TextView) findViewById(R.id.fileMeta);
        btnPick = (TextView) findViewById(R.id.btnPick);
        targetTitle = (TextView) findViewById(R.id.targetTitle);
        targetSub = (TextView) findViewById(R.id.targetSub);
        settingsPlaceholder = (TextView) findViewById(R.id.settingsPlaceholder);
        settingsContainer = (LinearLayout) findViewById(R.id.settingsContainer);
        sizeEstimate = (TextView) findViewById(R.id.sizeEstimate);
        btnConvert = (TextView) findViewById(R.id.btnConvert);
        resultCard = (LinearLayout) findViewById(R.id.resultCard);
        resultName = (TextView) findViewById(R.id.resultName);
        resultMeta = (TextView) findViewById(R.id.resultMeta);
        btnOpen = (TextView) findViewById(R.id.btnOpen);
        btnShare = (TextView) findViewById(R.id.btnShare);
        btnNew = (TextView) findViewById(R.id.btnNew);

        View.OnClickListener pick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        };
        cardFile.setOnClickListener(pick);
        btnPick.setOnClickListener(pick);
        cardTarget.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseTarget();
            }
        });
        btnConvert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startConversion();
            }
        });
        btnOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSaved();
            }
        });
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareSaved();
            }
        });
        btnNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resultCard.setVisibility(View.GONE);
                pickFile();
            }
        });

        pressable(cardFile);
        pressable(cardTarget);
        pressable(btnPick);
        pressable(btnConvert);

        refreshAll();
    }

    // ---------------- state ----------------

    private void refreshAll() {
        refreshFileCard();
        refreshTargetCard();
        buildSettings();
        updateConvertState();
    }

    private void refreshFileCard() {
        if (source == null) {
            fileName.setText("Файл не выбран");
            fileMeta.setText("Нажмите, чтобы выбрать файл");
            btnPick.setText("Выбрать");
            return;
        }
        fileName.setText(source.name);
        String size = source.size > 0 ? Util.humanSize(source.size) : "размер неизвестен";
        fileMeta.setText(size + " · " + source.categoryTitle());
        btnPick.setText("Заменить");
    }

    private void refreshTargetCard() {
        if (target == null) {
            targetTitle.setText("Не выбран");
            targetSub.setText(source == null ? "Сначала выберите файл" : "Выберите целевой формат");
            return;
        }
        targetTitle.setText(target.label + " · ." + target.ext);
        targetSub.setText(target.description);
    }

    private void updateConvertState() {
        boolean ready = source != null && target != null && !converting;
        btnConvert.setEnabled(ready);
        btnConvert.setBackgroundResource(ready ? R.drawable.bg_button : R.drawable.bg_button_disabled);
    }

    // ---------------- picking ----------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            toast("Не удалось открыть выбор файла");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        final ProgressDialogUi pd = new ProgressDialogUi(this);
        pd.show("Чтение файла", "Определяем тип…", false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final SourceFile sf = SourceFile.open(MainActivity.this, uri);
                    final SizeEstimator.Info info = SizeEstimator.probe(MainActivity.this, sf);
                    final List<Formats.FormatSpec> list = Convert.targetsFor(sf);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            source = sf;
                            sourceInfo = info;
                            targets = list;
                            target = null;
                            values.clear();
                            resultCard.setVisibility(View.GONE);
                            refreshAll();
                            scroll.smoothScrollTo(0, cardTarget.getTop());
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            showError(e.getMessage() == null ? "Не удалось прочитать файл" : e.getMessage());
                        }
                    });
                }
            }
        });
    }

    // ---------------- format picker ----------------

    private void chooseTarget() {
        if (source == null) {
            toast("Сначала выберите исходный файл");
            return;
        }
        if (targets.isEmpty()) {
            toast("Для этого файла нет доступных форматов");
            return;
        }
        final Dialog dialog = new Dialog(this);
        final List<Object> rows = new ArrayList<Object>();
        buildRows(rows, "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Формат");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(getColor(R.color.textPrimary));
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(title, tlp);
        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(getColor(R.color.textSecondary)));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        head.addView(close, new LinearLayout.LayoutParams(dp(24), dp(24)));
        root.addView(head);

        final EditText search = new EditText(this);
        search.setHint("Найти формат");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        search.setTextColor(getColor(R.color.textPrimary));
        search.setHintTextColor(getColor(R.color.textSecondary));
        search.setBackgroundResource(R.drawable.bg_input);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.textSecondary)));
        search.setCompoundDrawablePadding(dp(10));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        searchLp.topMargin = dp(14);
        root.addView(search, searchLp);

        final ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setVerticalScrollBarEnabled(false);
        final PickerAdapter adapter = new PickerAdapter(rows);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        llp.topMargin = dp(8);
        root.addView(list, llp);

        final TextView empty = new TextView(this);
        empty.setText("Ничего не найдено");
        empty.setTextColor(getColor(R.color.textSecondary));
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                rows.clear();
                buildRows(rows, s.toString());
                adapter.notifyDataSetChanged();
                empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });

        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Object row = rows.get(position);
                if (row instanceof Formats.FormatSpec) {
                    setTarget((Formats.FormatSpec) row);
                    dialog.dismiss();
                }
            }
        });

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.65f);
            int width = getResources().getDisplayMetrics().widthPixels - dp(28);
            int height = Math.min((int) (getResources().getDisplayMetrics().heightPixels * 0.84), dp(660));
            w.setLayout(width, height);
        }
    }

    private void buildRows(List<Object> rows, String query) {
        String q = query.trim().toLowerCase();
        Formats.Category current = null;
        for (Formats.FormatSpec f : targets) {
            if (!q.isEmpty()) {
                String hay = (f.label + " " + f.ext + " " + f.category.title + " " + f.description).toLowerCase();
                if (!hay.contains(q)) continue;
            }
            if (current != f.category) {
                current = f.category;
                rows.add(current);
            }
            rows.add(f);
        }
    }

    private void setTarget(Formats.FormatSpec f) {
        target = f;
        values = f.defaults();
        resultCard.setVisibility(View.GONE);
        refreshTargetCard();
        buildSettings();
        updateConvertState();
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.smoothScrollTo(0, cardSettings.getTop());
            }
        });
    }

    // ---------------- settings ----------------

    private void buildSettings() {
        settingsContainer.removeAllViews();
        if (target == null) {
            settingsPlaceholder.setVisibility(View.VISIBLE);
            settingsContainer.setVisibility(View.GONE);
            updateSizeEstimate();
            return;
        }
        settingsPlaceholder.setVisibility(View.GONE);
        settingsContainer.setVisibility(View.VISIBLE);
        boolean spreadsheet = source != null && ("xlsx".equals(source.kind) || "ods".equals(source.kind));
        int count = 0;
        boolean docSource = source != null && source.category == Formats.Category.DOCUMENT;
        boolean imageSource = source != null && source.category == Formats.Category.IMAGE;
        for (Formats.SettingSpec spec : target.settings) {
            if ("sheet".equals(spec.key) && !spreadsheet) continue;
            if ("srcEncoding".equals(spec.key) && docSource) continue;
            if (imageSource && ("audio".equals(spec.key) || "abitrate".equals(spec.key))) continue;
            if (!imageSource && "duration".equals(spec.key)) continue;
            switch (spec.kind) {
                case SLIDER:
                    addSlider(spec);
                    break;
                case CHOICE:
                    addChoice(spec);
                    break;
                case SWITCH:
                    addSwitch(spec);
                    break;
                default:
                    addText(spec);
                    break;
            }
            count++;
        }
        if (count == 0) {
            TextView tv = new TextView(this);
            tv.setText("Для этого формата дополнительная настройка не требуется.");
            tv.setTextColor(getColor(R.color.textSecondary));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            settingsContainer.addView(tv, topMargin(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        }
        updateSizeEstimate();
    }

    private void updateSizeEstimate() {
        if (sizeEstimate == null) return;
        if (source == null || target == null) {
            sizeEstimate.setVisibility(View.GONE);
            return;
        }
        long est = SizeEstimator.estimate(source, target, values,
                sourceInfo != null ? sourceInfo : new SizeEstimator.Info());
        if (est <= 0) {
            sizeEstimate.setVisibility(View.GONE);
            return;
        }
        String text = "После сжатия: ≈ " + Util.humanSize(est);
        if (source.size > 0 && est < source.size) {
            text += " · −" + Util.humanSize(source.size - est);
        }
        sizeEstimate.setText(text);
        sizeEstimate.setVisibility(View.VISIBLE);
    }

    private void addSlider(final Formats.SettingSpec spec) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        settingsContainer.addView(box, topMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(spec.title, 12.5f, getColor(R.color.textSecondary), false);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = label("", 12.5f, getColor(R.color.accent), true);
        head.addView(value);
        box.addView(head);

        int current = valueOfInt(spec, values.get(spec.key));
        final SeekBar bar = new SeekBar(this);
        bar.setMax((spec.max - spec.min) / Math.max(1, spec.step));
        bar.setProgress((current - spec.min) / Math.max(1, spec.step));
        bar.setProgressDrawable(getDrawable(R.drawable.bg_slider_track));
        bar.setThumb(getDrawable(R.drawable.bg_slider_thumb));
        bar.setSplitTrack(false);
        bar.setPadding(0, dp(8), 0, dp(8));
        value.setText(formatSlider(spec, current));
        box.addView(bar, topMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = spec.min + progress * spec.step;
                values.put(spec.key, v);
                value.setText(formatSlider(spec, v));
                updateSizeEstimate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private String formatSlider(Formats.SettingSpec spec, int v) {
        if ("quality".equals(spec.key)) return v + "%";
        if ("wrap".equals(spec.key)) return v == 0 ? "без переносов" : v + " симв.";
        return String.valueOf(v);
    }

    private void addChoice(final Formats.SettingSpec spec) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        settingsContainer.addView(box, topMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));
        box.addView(label(spec.title, 12.5f, getColor(R.color.textSecondary), false));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFillViewport(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, dp(2));
        hsv.addView(row);
        box.addView(hsv, topMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        Object current = values.get(spec.key);
        final List<TextView> chips = new ArrayList<TextView>();
        for (int i = 0; i < spec.labels.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(spec.labels[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            chip.setTag(index);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            row.addView(chip, lp);
            chips.add(chip);
            boolean selected = same(current, spec.values[i]);
            styleChip(chip, selected);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    values.put(spec.key, spec.values[index]);
                    updateSizeEstimate();
                    for (TextView c : chips) {
                        styleChip(c, (Integer) c.getTag() == indexFor(spec, values.get(spec.key)));
                    }
                    if ("sheet".equals(spec.key)) {
                    }
                }
            });
        }
    }

    private int indexFor(Formats.SettingSpec spec, Object value) {
        for (int i = 0; i < spec.values.length; i++) {
            if (same(value, spec.values[i])) return i;
        }
        return -1;
    }

    private boolean same(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        chip.setTextColor(selected ? getColor(R.color.accent) : getColor(R.color.textSecondary));
        chip.setTypeface(android.graphics.Typeface.create(selected ? "sans-serif-medium" : "sans-serif", android.graphics.Typeface.NORMAL));
    }

    private void addSwitch(final Formats.SettingSpec spec) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        settingsContainer.addView(row, topMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 18));
        row.addView(label(spec.title, 12.5f, getColor(R.color.textPrimary), false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        Object v = values.get(spec.key);
        sw.setChecked(v instanceof Boolean ? (Boolean) v : false);
        sw.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, new int[]{}},
                new int[]{getColor(R.color.accent), 0xFF6E747D}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFF3A421F, 0xFF26282D}));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                values.put(spec.key, isChecked);
                updateSizeEstimate();
            }
        });
        row.addView(sw);
    }

    private void addText(final Formats.SettingSpec spec) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        settingsContainer.addView(box, topMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));
        box.addView(label(spec.title, 12.5f, getColor(R.color.textSecondary), false));
        EditText et = new EditText(this);
        Object v = values.get(spec.key);
        et.setText(v == null ? "" : String.valueOf(v));
        if (spec.hint != null) et.setHint(spec.hint);
        et.setSingleLine(true);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setTextColor(getColor(R.color.textPrimary));
        et.setHintTextColor(getColor(R.color.textSecondary));
        et.setBackgroundResource(R.drawable.bg_input);
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        if ("width".equals(spec.key) || "height".equals(spec.key)) {
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
        } else if ("trimStart".equals(spec.key) || "trimEnd".equals(spec.key) || "sheet".equals(spec.key)) {
            et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }
        box.addView(et, topMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                values.put(spec.key, s.toString().trim());
                updateSizeEstimate();
            }
        });
    }

    private TextView label(String text, float size, int color, boolean medium) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        tv.setTextColor(color);
        if (medium) tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        return tv;
    }

    private LinearLayout.LayoutParams topMargin(int w, int h, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = dp(margin);
        return lp;
    }

    private int valueOfInt(Formats.SettingSpec spec, Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        return spec.def;
    }

    // ---------------- conversion ----------------

    private void startConversion() {
        if (converting) return;
        if (source == null) {
            toast("Выберите файл");
            return;
        }
        if (target == null) {
            chooseTarget();
            return;
        }
        final SourceFile s = source;
        final Formats.FormatSpec t = target;
        final Map<String, Object> vals = new LinkedHashMap<String, Object>(values);
        converting = true;
        updateConvertState();
        final ProgressDialogUi pd = new ProgressDialogUi(this);
        pd.show("Конвертация", t.label, true);

        final Util.Progress progress = new Util.Progress() {
            @Override
            public void onProgress(final int percent, final String message) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        pd.update(percent, message);
                    }
                });
            }

            @Override
            public boolean isCancelled() {
                return pd.isCancelled();
            }
        };

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Output out = Convert.run(s, t, vals, progress);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.update(99, "Сохранение в Downloads…");
                        }
                    });
                    final List<Exporter.Saved> saved = Exporter.export(MainActivity.this, out);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            converting = false;
                            updateConvertState();
                            showResult(out, saved);
                        }
                    });
                } catch (InterruptedException ie) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            converting = false;
                            updateConvertState();
                            toast("Конвертация отменена");
                        }
                    });
                } catch (final Throwable e) {
                    final String msg = e.getMessage() == null ? "Неизвестная ошибка" : e.getMessage();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            converting = false;
                            updateConvertState();
                            showError(msg);
                        }
                    });
                }
            }
        });
    }

    private void showResult(Output out, List<Exporter.Saved> saved) {
        lastSaved = saved;
        if (saved.isEmpty()) {
            showError("Не удалось сохранить результат");
            return;
        }
        resultCard.setVisibility(View.VISIBLE);
        if (saved.size() == 1) {
            Exporter.Saved one = saved.get(0);
            resultName.setText(one.name);
            StringBuilder meta = new StringBuilder();
            if (out.summary != null && out.summary.length() > 0) meta.append(out.summary).append("\n");
            if (source != null && source.size > 0 && one.size > 0) {
                meta.append("Было: ").append(Util.humanSize(source.size));
                long savedBytes = source.size - one.size;
                if (savedBytes > 0) meta.append(" · −").append(Util.humanSize(savedBytes));
                meta.append("\n");
            }
            meta.append(Exporter.niceFolder(one.folder)).append("/").append(one.name);
            resultMeta.setText(meta.toString());
            btnOpen.setText("Открыть");
        } else {
            resultName.setText("Извлечено файлов: " + saved.size());
            String folderName = out.folder != null ? out.folder : saved.get(0).folder;
            resultMeta.setText("Папка: Downloads/FormatForge/" + folderName);
            btnOpen.setText("Список");
        }
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.smoothScrollTo(0, resultCard.getTop() - dp(8));
            }
        });
        toast("Сохранено в Downloads/FormatForge");
    }

    private void openSaved() {
        if (lastSaved == null || lastSaved.isEmpty()) return;
        if (lastSaved.size() == 1) {
            openUri(lastSaved.get(0));
            return;
        }
        final Dialog d = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.addView(label("Файлы результата", 16, getColor(R.color.textPrimary), true));
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setVerticalScrollBarEnabled(false);
        final List<Exporter.Saved> items = lastSaved;
        lv.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public Object getItem(int position) {
                return items.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Exporter.Saved s = items.get(position);
                TextView tv = new TextView(MainActivity.this);
                tv.setText(s.name);
                tv.setTextColor(getColor(R.color.textPrimary));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tv.setPadding(dp(8), dp(14), dp(8), dp(14));
                tv.setBackgroundResource(R.drawable.bg_row);
                return tv;
            }
        });
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                openUri(items.get(position));
                d.dismiss();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(10);
        root.addView(lv, lp);
        d.setContentView(root);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(getResources().getDisplayMetrics().widthPixels - dp(48), dp(420));
        }
    }

    private void openUri(Exporter.Saved s) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(s.uri, s.mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Открыть"));
        } catch (Exception e) {
            toast("Нет приложения для этого формата");
        }
    }

    private void shareSaved() {
        if (lastSaved == null || lastSaved.isEmpty()) return;
        try {
            if (lastSaved.size() == 1) {
                Exporter.Saved s = lastSaved.get(0);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(s.mime);
                i.putExtra(Intent.EXTRA_STREAM, s.uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.setClipData(ClipData.newRawUri(s.name, s.uri));
                startActivity(Intent.createChooser(i, "Поделиться"));
            } else {
                ArrayList<Uri> uris = new ArrayList<Uri>();
                for (Exporter.Saved s : lastSaved) uris.add(s.uri);
                Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.setType("*/*");
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Поделиться"));
            }
        } catch (Exception e) {
            toast("Не удалось поделиться");
        }
    }

    // ---------------- misc UI ----------------

    private void showError(String message) {
        final Dialog d = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog);
        root.setPadding(dp(22), dp(22), dp(22), dp(20));

        TextView title = label("Что-то пошло не так", 16, getColor(R.color.textPrimary), true);
        root.addView(title);

        TextView msg = label(message, 13, getColor(R.color.textSecondary), false);
        msg.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(10);
        root.addView(msg, mlp);

        TextView ok = new TextView(this);
        ok.setText("Понятно");
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ok.setTextColor(getColor(R.color.accent));
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, dp(12), 0, dp(4));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(8);
        root.addView(ok, olp);

        d.setContentView(root);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(getResources().getDisplayMetrics().widthPixels - dp(56), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void pressable(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        break;
                }
                return false;
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ---------------- picker adapter ----------------

    private final class PickerAdapter extends BaseAdapter {
        private final List<Object> rows;

        PickerAdapter(List<Object> rows) {
            this.rows = rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof Formats.Category ? 0 : 1;
        }

        @Override
        public boolean isEnabled(int position) {
            return rows.get(position) instanceof Formats.FormatSpec;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object row = rows.get(position);
            if (row instanceof Formats.Category) {
                Formats.Category c = (Formats.Category) row;
                TextView tv = new TextView(MainActivity.this);
                tv.setText(c.title.toUpperCase());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
                tv.setTextColor(getColor(R.color.textSecondary));
                tv.setLetterSpacing(0.15f);
                tv.setPadding(dp(0), dp(20), dp(0), dp(7));
                return tv;
            }
            Formats.FormatSpec f = (Formats.FormatSpec) row;
            LinearLayout item = new LinearLayout(MainActivity.this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackgroundResource(R.drawable.bg_row);
            item.setPadding(dp(0), dp(10), dp(0), dp(10));

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.rightMargin = dp(12);
            item.addView(texts, tlp);

            TextView name = new TextView(MainActivity.this);
            name.setText(f.label);
            name.setTextColor(getColor(R.color.textPrimary));
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            texts.addView(name);

            TextView desc = new TextView(MainActivity.this);
            desc.setText(f.description);
            desc.setTextColor(getColor(R.color.textSecondary));
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            desc.setMaxLines(1);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(desc);

            TextView ext = new TextView(MainActivity.this);
            ext.setText("." + f.ext);
            ext.setTextColor(getColor(R.color.textSecondary));
            ext.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            ext.setTypeface(android.graphics.Typeface.MONOSPACE);
            item.addView(ext);

            return item;
        }
    }

    // ---------------- progress dialog ----------------

    private final class ProgressDialogUi {
        private final Dialog dialog;
        private final ProgressBar bar;
        private final TextView percentText;
        private final TextView messageText;
        private final TextView cancel;
        private volatile boolean cancelled;

        ProgressDialogUi(Activity activity) {
            dialog = new Dialog(activity);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.bg_dialog);
            root.setPadding(dp(22), dp(20), dp(22), dp(16));

            TextView title = label("Конвертация", 16, getColor(R.color.textPrimary), true);
            root.addView(title);

            messageText = label("Подготовка…", 12, getColor(R.color.textSecondary), false);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(6);
            root.addView(messageText, mlp);

            bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2A2D32));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
            blp.topMargin = dp(16);
            root.addView(bar, blp);

            percentText = label("0%", 12, getColor(R.color.textSecondary), true);
            percentText.setGravity(Gravity.END);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = dp(8);
            root.addView(percentText, plp);

            cancel = new TextView(activity);
            cancel.setText("Отмена");
            cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            cancel.setTextColor(getColor(R.color.textSecondary));
            cancel.setGravity(Gravity.CENTER);
            cancel.setPadding(0, dp(12), 0, dp(4));
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cancelled = true;
                    cancel.setText("Отмена…");
                    cancel.setEnabled(false);
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(6);
            root.addView(cancel, clp);

            dialog.setContentView(root);
            dialog.setCancelable(false);
        }

        void show(String title, String message, boolean cancellable) {
            if (dialog.isShowing()) return;
            dialog.show();
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setDimAmount(0.6f);
                w.setLayout(getResources().getDisplayMetrics().widthPixels - dp(72), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            cancel.setVisibility(cancellable ? View.VISIBLE : View.GONE);
            update(0, message);
        }

        void update(int percent, String message) {
            if (!dialog.isShowing()) return;
            int p = Math.max(0, Math.min(100, percent));
            bar.setProgress(p);
            percentText.setText(p + "%");
            if (message != null && !message.isEmpty()) messageText.setText(message);
        }

        boolean isCancelled() {
            return cancelled;
        }

        void dismiss() {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Exception ignored) {
            }
        }
    }
}
