package ru.app4dad.windows;

import ru.app4dad.core.SyncEngine;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;

public final class Main {
    private final Controller controller=new Controller();
    private RemoteServer remote;
    private final JFrame frame=new JFrame("Михеев · Копирование на флешку");
    private final JTextField source=new JTextField("S:\\Михеев"),target=new JTextField("E:\\Михеев");
    private final JTextField from=new JTextField(10),to=new JTextField(10);
    private final JButton scan=new JButton("1. Сравнить папки"),copy=new JButton("2. Скопировать выбранное"),cancel=new JButton("Остановить");
    private final JLabel status=new JLabel(" "),summary=new JLabel("Сначала сравните папки — файлы пока не копируются");
    private final JProgressBar progress=new JProgressBar();
    private final DefaultTableModel model=new DefaultTableModel(new Object[]{"Выбор","Статус","Файл / подпапка","Размер","Причина"},0) {
        @Override public Class<?> getColumnClass(int c) { return c==0?Boolean.class:String.class; }
        @Override public boolean isCellEditable(int r,int c) { return c==0&&!controller.busy()&&shown!=null&&shown.items.get(r).copyable(); }
    };
    private SyncEngine.Plan shown;
    private boolean updatingDates;
    private final JTable table=new JTable(model);
    private final List<JComponent> settings=new ArrayList<>();
    private final DateTimeFormatter dates=DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private final javax.swing.Timer timer=new javax.swing.Timer(300,e->refresh());
    private Main() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) {
            if(controller.busy()&&JOptionPane.showConfirmDialog(frame,"Остановить текущую операцию и закрыть приложение?","Закрытие",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION) return;
            timer.stop(); if(remote!=null) remote.close(); controller.close(); frame.dispose();
        }});
        JPanel root=new JPanel(new BorderLayout(16,16)); root.setBorder(new EmptyBorder(24,28,24,28)); root.setBackground(new Color(245,247,250));
        JPanel top=new JPanel(); top.setOpaque(false); top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JLabel title=new JLabel("На флешке — актуальная копия"); title.setFont(new Font("Segoe UI",Font.BOLD,27)); top.add(title);
        JLabel subtitle=new JLabel("Вручную · с подпапками · только за выбранные даты"); subtitle.setForeground(new Color(80,95,113)); top.add(subtitle); top.add(Box.createVerticalStrut(22));
        top.add(pathRow("Откуда",source)); top.add(Box.createVerticalStrut(10)); top.add(pathRow("Куда",target)); top.add(Box.createVerticalStrut(16));
        JPanel period=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0)); period.setOpaque(false);
        period.add(new JLabel("Дата изменения: с")); period.add(from); period.add(new JLabel("по")); period.add(to);
        JButton today=new JButton("Сегодня"),yesterday=new JButton("Вчера и сегодня"),all=new JButton("Все даты");
        today.addActionListener(e->setDates(LocalDate.now(),LocalDate.now())); yesterday.addActionListener(e->setDates(LocalDate.now().minusDays(1),LocalDate.now()));
        all.addActionListener(e->setDates(LocalDate.of(1970,1,1),LocalDate.now().plusYears(100)));
        period.add(today); period.add(yesterday); period.add(all); top.add(period); top.add(Box.createVerticalStrut(12));
        JLabel rule=new JLabel("Обе даты включены. Конфликты и более новые версии на флешке не перезаписываются."); rule.setForeground(new Color(80,95,113)); top.add(rule);
        setDates(LocalDate.now().minusDays(1),LocalDate.now()); settings.addAll(Arrays.asList(source,target,from,to,today,yesterday,all));
        DocumentListener changed=new DocumentListener() {
            private void update() { if(!updatingDates&&!controller.busy()) { controller.configure(source.getText().trim(),target.getText().trim());controller.invalidatePlan(); } }
            public void insertUpdate(DocumentEvent e) { update(); } public void removeUpdate(DocumentEvent e) { update(); } public void changedUpdate(DocumentEvent e) { update(); }
        };
        for(JTextField field:Arrays.asList(source,target,from,to))field.getDocument().addDocumentListener(changed);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,8,14)); actions.setOpaque(false); actions.add(scan); actions.add(copy); actions.add(cancel);
        JButton log=new JButton("Журнал"); log.addActionListener(e->showLog()); actions.add(log); top.add(actions); top.add(summary); root.add(top,BorderLayout.NORTH);
        table.setRowHeight(32); table.setAutoCreateRowSorter(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false); table.getColumnModel().getColumn(0).setMaxWidth(95); table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(350); table.getColumnModel().getColumn(3).setPreferredWidth(95); table.getColumnModel().getColumn(4).setPreferredWidth(300);
        root.add(new JScrollPane(table),BorderLayout.CENTER);
        JPanel footer=new JPanel(); footer.setOpaque(false); footer.setLayout(new BoxLayout(footer,BoxLayout.Y_AXIS));
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE,8)); footer.add(progress); footer.add(Box.createVerticalStrut(8)); footer.add(status); footer.add(Box.createVerticalStrut(14));
        JButton phone=new JButton("Подключить Android-пульт"); phone.addActionListener(e->remoteDialog(phone)); footer.add(phone); root.add(footer,BorderLayout.SOUTH);
        scan.addActionListener(e->{ try { controller.configure(source.getText().trim(),target.getText().trim()); controller.scan(LocalDate.parse(from.getText().trim(),dates),LocalDate.parse(to.getText().trim(),dates)); refresh(); } catch(Exception ex) { error(ex); } });
        copy.addActionListener(e->copySelected()); cancel.addActionListener(e->controller.cancel());
        frame.setContentPane(root); frame.setMinimumSize(new Dimension(900,650)); frame.setSize(1120,800); frame.setLocationRelativeTo(null); refresh(); timer.start(); frame.setVisible(true);
    }
    private JPanel pathRow(String label,JTextField field) {
        JPanel row=new JPanel(new BorderLayout(10,0)); row.setOpaque(false); JLabel l=new JLabel(label); l.setPreferredSize(new Dimension(65,35)); row.add(l,BorderLayout.WEST); row.add(field,BorderLayout.CENTER);
        JButton browse=new JButton("Выбрать…"); settings.add(browse); browse.addActionListener(e->{ JFileChooser chooser=new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); chooser.setDialogTitle(label+": выберите папку"); if(chooser.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION) { field.setText(chooser.getSelectedFile().getAbsolutePath()); try { controller.configure(source.getText().trim(),target.getText().trim()); refresh(); } catch(Exception ex) { error(ex); } } }); row.add(browse,BorderLayout.EAST); row.setMaximumSize(new Dimension(Integer.MAX_VALUE,38)); return row;
    }
    private void setDates(LocalDate f,LocalDate t) { from.setText(dates.format(f)); to.setText(dates.format(t)); }
    private void refresh() {
        Properties p=controller.state(0); boolean busy=controller.busy(); status.setText(p.getProperty("status")); progress.setIndeterminate(busy);
        for(JComponent setting:settings) setting.setEnabled(!busy);
        scan.setEnabled(!busy); cancel.setEnabled(busy);
        SyncEngine.Plan current=controller.plan(); copy.setEnabled(!busy&&current!=null&&current.count()>0);
        if(current!=shown) {
            shown=current; model.setRowCount(0);
            if(current!=null) {
                updatingDates=true;
                try { setDates(LocalDate.parse(p.getProperty("from")),LocalDate.parse(p.getProperty("to"))); } finally { updatingDates=false; }
                for(SyncEngine.Item i:current.items) model.addRow(new Object[]{i.copyable(),SyncEngine.label(i.kind),i.path,size(i.size),i.message});
                long conflicts=current.items.stream().filter(i->!i.copyable()).count();
                summary.setText("К копированию: "+current.count()+" файлов · "+size(current.bytes())+"   |   Конфликтов / ошибок: "+conflicts+"   |   Совпадают: "+current.identical);
            } else summary.setText(busy?"Операция выполняется…":"Для нового копирования снова сравните папки");
        }
    }
    private void copySelected() {
        if(table.isEditing()) table.getCellEditor().stopCellEditing();
        SyncEngine.Plan p=shown; if(p==null) return;
        Set<String> selected=new HashSet<>(); long bytes=0;
        for(int r=0;r<model.getRowCount();r++) if(Boolean.TRUE.equals(model.getValueAt(r,0))&&p.items.get(r).copyable()) { selected.add(p.items.get(r).path); bytes+=p.items.get(r).size; }
        if(selected.isEmpty()) { error(new Exception("Выберите файлы галочками")); return; }
        String prompt="Скопировать "+selected.size()+" файлов ("+size(bytes)+")?\n\n"+p.source+"\n→ "+p.target+"\n\nИзменённые файлы будут заменены. Удалений нет.";
        if(JOptionPane.showConfirmDialog(frame,prompt,"Подтверждение копирования",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        try { controller.copy(p.id,selected); refresh(); } catch(Exception e) { error(e); }
    }
    private void remoteDialog(JButton button) {
        try {
            if(remote==null) remote=new RemoteServer(controller,48123);
            JTextField address=new JTextField(RemoteServer.addresses(remote.port())); address.setEditable(false);
            JTextField code=new JTextField(remote.code); code.setEditable(false);
            JPanel panel=new JPanel(new GridLayout(0,1,0,8)); panel.add(new JLabel("Телефон и компьютер должны быть в одной локальной сети.")); panel.add(new JLabel("В Android укажите один из адресов:")); panel.add(address); panel.add(new JLabel("Код сопряжения (можно скопировать):")); panel.add(code);
            JButton clipboard=new JButton("Скопировать код"); clipboard.addActionListener(e->Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(remote.code),null)); panel.add(clipboard);
            panel.add(new JLabel("Пульт может сравнивать папки и запускать копирование. Код действует до отключения."));
            int answer=JOptionPane.showOptionDialog(frame,panel,"Android-пульт включён",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,new String[]{"Оставить включённым","Отключить пульт"},"Оставить включённым");
            if(answer==1) { remote.close(); remote=null; }
            button.setText(remote==null?"Подключить Android-пульт":"Android-пульт включён · настройки");
        } catch(Exception e) { error(e); }
    }
    private void showLog() {
        JTextArea area=new JTextArea(controller.log().isEmpty()?"Журнал появится после копирования":controller.log(),18,75); area.setEditable(false); area.setCaretPosition(0);
        int choice=JOptionPane.showOptionDialog(frame,new JScrollPane(area),"Журнал копирования",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,new String[]{"Закрыть","Сохранить…"},"Закрыть");
        if(choice==1) { JFileChooser chooser=new JFileChooser(); chooser.setSelectedFile(new java.io.File("App4Dad-журнал.txt")); if(chooser.showSaveDialog(frame)==JFileChooser.APPROVE_OPTION) { Path file=chooser.getSelectedFile().toPath(); if(Files.exists(file)&&JOptionPane.showConfirmDialog(frame,"Заменить существующий журнал?","Сохранение",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return; try { Files.write(file,area.getText().getBytes(StandardCharsets.UTF_8)); } catch(Exception e) { error(e); } } }
    }
    private void error(Exception e) { JOptionPane.showMessageDialog(frame,Controller.message(e),"Не удалось выполнить действие",JOptionPane.ERROR_MESSAGE); }
    static String size(long b) { if(b<1024) return b+" Б"; if(b<1024*1024) return String.format(Locale.ROOT,"%.1f КБ",b/1024d); return String.format(Locale.ROOT,"%.1f МБ",b/1048576d); }
    public static void main(String[] args) {
        UIManager.put("defaultFont",new Font("Segoe UI",Font.PLAIN,14));
        for(Object k:UIManager.getDefaults().keySet().toArray()) if(k.toString().endsWith(".font")) UIManager.put(k,new Font("Segoe UI",Font.PLAIN,14));
        SwingUtilities.invokeLater(Main::new);
    }
}
