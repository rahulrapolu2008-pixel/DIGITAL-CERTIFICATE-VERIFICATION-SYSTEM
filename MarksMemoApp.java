import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.List;

/** Marks Memo Generation and Verification System. Demo records are fictional. */
public class MarksMemoApp {
    private static final String TITLE = "Marks Memo Generation and Verification System";
    private static final String INSTITUTION_NAME = "Geethanjali College of Engineering";
    private static final String CONFIG = "config/db.properties";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JFrame frame = new JFrame(TITLE);
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Roll number", "Student", "Branch", "Course", "Admission year"}, 0) {
        public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable students = new JTable(model);
    private JTextField search;

    public static void main(String[] args) { SwingUtilities.invokeLater(() -> { MarksMemoApp app = new MarksMemoApp(); if (app.login()) app.show(); }); }

    /** Password-only sign-in for the single local administrator account. */
    private boolean login() {
        try {
            if (userCount() == 0 && !createFirstAccount()) return false;
            for (int attempt = 0; attempt < 5; attempt++) {
                JPasswordField password = new JPasswordField();
                JPanel fields = form("Administrator password", password);
                int choice = JOptionPane.showConfirmDialog(null, fields, "Sign in", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                if (choice != JOptionPane.OK_OPTION) return false;
                char[] enteredPassword = password.getPassword();
                boolean valid = verifyPassword(enteredPassword);
                Arrays.fill(enteredPassword, '\0');
                if (valid) return true;
                error("Password is incorrect.");
            }
            error("Too many sign-in attempts. Restart the application to try again.");
        } catch (Exception ex) { statusError(ex); }
        return false;
    }

    private int userCount() throws Exception {
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM app_users")) { r.next(); return r.getInt(1); }
    }

    private boolean createFirstAccount() throws Exception {
        JPasswordField password = new JPasswordField(); JPasswordField confirm = new JPasswordField();
        JPanel fields = form("Create password (12+ characters)", password, "Confirm password", confirm);
        JOptionPane.showMessageDialog(null, "Create the administrator password. This setup appears only while there are no accounts.", "Initial password setup", JOptionPane.INFORMATION_MESSAGE);
        if (JOptionPane.showConfirmDialog(null, fields, "Create administrator password", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return false;
        char[] pw = password.getPassword(), pw2 = confirm.getPassword();
        try {
            if (pw.length < 12 || !Arrays.equals(pw, pw2)) throw new IllegalArgumentException("Use a password of at least 12 characters and confirm it correctly.");
            byte[] salt = new byte[16]; RANDOM.nextBytes(salt); byte[] hash = passwordHash(pw, salt);
            try (Connection c = db(); PreparedStatement p = c.prepareStatement("INSERT INTO app_users(username,email,password_salt,password_hash) VALUES(?,?,?,?)")) {
                p.setString(1, "admin"); p.setString(2, "admin@localhost.invalid"); p.setBytes(3, salt); p.setBytes(4, hash); p.executeUpdate();
            }
            JOptionPane.showMessageDialog(null, "Administrator password created. Now sign in using that password."); return true;
        } finally { Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0'); }
    }

    private boolean verifyPassword(char[] password) throws Exception {
        try (Connection c = db(); Statement p = c.createStatement(); ResultSet r = p.executeQuery("SELECT password_salt,password_hash FROM app_users WHERE active=TRUE ORDER BY created_at LIMIT 1")) {
                if (!r.next()) return false;
                byte[] actual = passwordHash(password, r.getBytes(1));
                return MessageDigest.isEqual(actual, r.getBytes(2));
        }
    }

    private byte[] passwordHash(char[] password, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(password, salt, 210_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { ((PBEKeySpec) spec).clearPassword(); }
    }

    private void show() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 600)); frame.setSize(1100, 720); frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());
        JPanel top = new JPanel(new BorderLayout()); top.setBackground(new Color(24, 48, 78)); top.setBorder(new EmptyBorder(18, 22, 18, 22));
        JLabel title = new JLabel(INSTITUTION_NAME + "  |  " + TITLE + "  [DEMO]"); title.setForeground(Color.WHITE); title.setFont(new Font("SansSerif", Font.BOLD, 20)); top.add(title);
        frame.add(top, BorderLayout.NORTH);
        JPanel nav = new JPanel(new GridLayout(0, 1, 5, 8)); nav.setBorder(new EmptyBorder(16, 12, 16, 12)); nav.setPreferredSize(new Dimension(205, 0));
        addNav(nav, "Dashboard", this::refresh); addNav(nav, "Register student", this::registerStudent); addNav(nav, "Add semester results", this::addResults);
        addNav(nav, "Issue semester memo", this::issueSemester); addNav(nav, "Issue consolidated memo", this::issueConsolidated);
        addNav(nav, "Verify memo / file", this::verify); addNav(nav, "Delete student", this::deleteStudent); addNav(nav, "Database status", this::dbStatus);
        frame.add(nav, BorderLayout.WEST);
        JPanel center = new JPanel(new BorderLayout(8, 8)); center.setBorder(new EmptyBorder(18, 18, 18, 18));
        JPanel searchBar = new JPanel(new BorderLayout(8, 0)); search = new JTextField(); JButton find = new JButton("Search"); find.addActionListener(e -> refresh());
        searchBar.add(new JLabel("Search students: "), BorderLayout.WEST); searchBar.add(search, BorderLayout.CENTER); searchBar.add(find, BorderLayout.EAST);
        center.add(searchBar, BorderLayout.NORTH); students.setRowHeight(28); center.add(new JScrollPane(students), BorderLayout.CENTER);
        JLabel note = new JLabel("Grade points: 10=A+, 9=A, 8=B+, 7=B, 6=C, 5=D, 0–4=F. A subject passes with grade point ≥5."); note.setBorder(new EmptyBorder(8, 0, 0, 0)); center.add(note, BorderLayout.SOUTH);
        frame.add(center, BorderLayout.CENTER); frame.setVisible(true); refresh();
    }
    private void addNav(JPanel p, String text, Runnable action) { JButton b = new JButton(text); b.setFocusPainted(false); b.addActionListener(e -> action.run()); p.add(b); }
    private Connection db() throws Exception {
        Properties p = new Properties(); try (InputStream in = Files.newInputStream(Path.of(CONFIG))) { p.load(in); }
        return DriverManager.getConnection(p.getProperty("db.url"), p.getProperty("db.user"), p.getProperty("db.password", ""));
    }
    private void refresh() {
        model.setRowCount(0);
        try (Connection c = db(); PreparedStatement ps = c.prepareStatement("SELECT roll_no,name,branch,course,admission_year FROM students WHERE roll_no LIKE ? OR name LIKE ? ORDER BY name")) {
            String q = search == null ? "" : search.getText().trim(); ps.setString(1, "%" + q + "%"); ps.setString(2, "%" + q + "%");
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) model.addRow(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)}); }
        } catch (Exception e) { statusError(e); }
    }
    private void registerStudent() {
        JTextField name = new JTextField(), roll = new JTextField(), branch = new JTextField(), course = new JTextField(), year = new JTextField();
        JTextField photo = new JTextField(); JButton browse = new JButton("Browse…"); browse.addActionListener(e -> { JFileChooser fc = new JFileChooser(); if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) photo.setText(fc.getSelectedFile().getAbsolutePath()); });
        JPanel form = form("Student name", name, "Roll number", roll, "Branch", branch, "Course", course, "Admission year", year, "Photo file (optional)", photo);
        form.add(browse); if (JOptionPane.showConfirmDialog(frame, form, "Register student", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            if (name.getText().isBlank() || roll.getText().isBlank() || branch.getText().isBlank() || course.getText().isBlank()) throw new IllegalArgumentException("Name, roll number, branch and course are required.");
            int y = Integer.parseInt(year.getText().trim()); if (y < 1900 || y > LocalDate.now().getYear() + 1) throw new IllegalArgumentException("Admission year is out of range.");
            byte[] img = photo.getText().isBlank() ? null : Files.readAllBytes(Path.of(photo.getText().trim()));
            try (Connection c = db(); PreparedStatement ps = c.prepareStatement("INSERT INTO students(roll_no,name,branch,course,admission_year,photo) VALUES(?,?,?,?,?,?)")) {
                ps.setString(1, roll.getText().trim()); ps.setString(2, name.getText().trim()); ps.setString(3, branch.getText().trim()); ps.setString(4, course.getText().trim()); ps.setInt(5, y); ps.setBytes(6, img); ps.executeUpdate();
            } JOptionPane.showMessageDialog(frame, "Student registered."); refresh();
        } catch (SQLIntegrityConstraintViolationException ex) { error("That roll number is already registered."); }
        catch (Exception ex) { statusError(ex); }
    }
    private JPanel form(Object... pairs) { JPanel p = new JPanel(new GridLayout(0, 2, 8, 8)); p.setBorder(new EmptyBorder(8, 8, 8, 8)); for (int i=0;i<pairs.length;i+=2) { p.add(new JLabel((String)pairs[i])); p.add((Component)pairs[i+1]); } return p; }
    private String selectedRoll() { int row=students.getSelectedRow(); return row < 0 ? null : String.valueOf(model.getValueAt(students.convertRowIndexToModel(row),0)); }
    private void addResults() {
        String roll=selectedRoll(); if(roll==null){error("Select a student in the dashboard first.");return;}
        JTextField sem=new JTextField(), code=new JTextField(), name=new JTextField(), credits=new JTextField(), gradePoint=new JTextField();
        JPanel p=form("Semester (1–12)",sem,"Subject code",code,"Subject name",name,"Credits (1–10)",credits,"Grade point (0–10)",gradePoint);
        if(JOptionPane.showConfirmDialog(frame,p,"Add subject result for "+roll,JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        try { int s=Integer.parseInt(sem.getText()), gp=Integer.parseInt(gradePoint.getText()); int cr=Integer.parseInt(credits.getText()); if(s<1||s>12||gp<0||gp>10||cr<1||cr>10||code.getText().isBlank()||name.getText().isBlank())throw new IllegalArgumentException("Check semester, subject code/name, credits and grade point (0–10).");
            try(Connection c=db();PreparedStatement ps=c.prepareStatement("INSERT INTO results(roll_no,semester,subject_code,subject_name,credits,grade,grade_point,passed) VALUES(?,?,?,?,?,?,?,?)")) { ps.setString(1,roll);ps.setInt(2,s);ps.setString(3,code.getText().trim());ps.setString(4,name.getText().trim());ps.setInt(5,cr);ps.setString(6,grade(gp));ps.setInt(7,gp);ps.setBoolean(8,gp>=5);ps.executeUpdate(); } JOptionPane.showMessageDialog(frame,"Result saved. Semester SGPA: "+String.format(Locale.ROOT,"%.2f",sgpa(roll,s))+"; CGPA: "+String.format(Locale.ROOT,"%.2f",cgpa(roll)));
        } catch(SQLIntegrityConstraintViolationException ex){error("That subject is already recorded for this semester.");}catch(Exception ex){statusError(ex);}
    }
    private String grade(int p){return p==10?"A+":p==9?"A":p==8?"B+":p==7?"B":p==6?"C":p==5?"D":"F";}
    private double semesterGpa(Connection c,String roll,int sem)throws Exception{try(PreparedStatement p=c.prepareStatement("SELECT SUM(credits*grade_point)/SUM(credits) FROM results WHERE roll_no=? AND semester=?")){p.setString(1,roll);p.setInt(2,sem);try(ResultSet r=p.executeQuery()){return r.next()?r.getDouble(1):0;}}}
    private double sgpa(String roll,int sem)throws Exception{try(Connection c=db()){return semesterGpa(c,roll,sem);}}
    private double cgpa(String roll)throws Exception{try(Connection c=db();PreparedStatement p=c.prepareStatement("SELECT SUM(credits*grade_point)/SUM(credits) FROM results WHERE roll_no=?")){p.setString(1,roll);try(ResultSet r=p.executeQuery()){return r.next()?r.getDouble(1):0;}}}
    private void issueSemester(){String roll=selectedRoll();if(roll==null){error("Select a student in the dashboard first.");return;}String value=JOptionPane.showInputDialog(frame,"Semester number:");if(value==null)return;try{int s=Integer.parseInt(value);generateMemo(roll,s,false);}catch(Exception e){statusError(e);}}
    private void issueConsolidated(){String roll=selectedRoll();if(roll==null){error("Select a student in the dashboard first.");return;}try{generateMemo(roll,0,true);}catch(Exception e){statusError(e);}}
    private record Subject(String code,String name,int credits,int gradePoint,String grade,boolean passed){}
    private void generateMemo(String roll,int semester,boolean all)throws Exception{
        String memo="MM-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT); Path out=Path.of("memos",memo+".pdf");Files.createDirectories(out.getParent());
        try(Connection c=db();PreparedStatement student=c.prepareStatement("SELECT name,branch,course,admission_year,photo FROM students WHERE roll_no=?")){
            student.setString(1,roll);String name,branch,course;int year;byte[] photo;
            try(ResultSet r=student.executeQuery()){if(!r.next())throw new IllegalArgumentException("Student not found.");name=r.getString(1);branch=r.getString(2);course=r.getString(3);year=r.getInt(4);photo=r.getBytes(5);}
            String sql="SELECT semester,subject_code,subject_name,credits,grade_point,grade,passed FROM results WHERE roll_no=?"+(all?"":" AND semester=?")+" ORDER BY semester,subject_code";
            List<Object[]> rows=new ArrayList<>();try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,roll);if(!all)p.setInt(2,semester);try(ResultSet r=p.executeQuery()){while(r.next())rows.add(new Object[]{r.getInt(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5),r.getString(6),r.getBoolean(7)});}}
            if(rows.isEmpty())throw new IllegalArgumentException("No subject results found for this memo.");
            createPdf(out,memo,roll,name,branch,course,year,photo,rows,all,c);
            String hash=sha256(out);try(PreparedStatement p=c.prepareStatement("INSERT INTO issued_memos(memo_id,roll_no,semester,consolidated,file_path,sha256) VALUES(?,?,?,?,?,?)")){p.setString(1,memo);p.setString(2,roll);if(all)p.setNull(3,Types.INTEGER);else p.setInt(3,semester);p.setBoolean(4,all);p.setString(5,out.toAbsolutePath().normalize().toString());p.setString(6,hash);p.executeUpdate();}
            JOptionPane.showMessageDialog(frame,"Memo issued: "+out.toAbsolutePath()+"\nSHA-256: "+hash);
        }
    }
    private void createPdf(Path out,String id,String roll,String name,String branch,String course,int year,byte[] photo,List<Object[]> rows,boolean all,Connection c)throws Exception{
        Path qr=Files.createTempFile("marks-memo-qr-",".png");
        MatrixToImageWriter.writeToPath(new QRCodeWriter().encode("MARKS-MEMO:"+id,BarcodeFormat.QR_CODE,180,180),"PNG",qr);
        try(PDDocument doc=new PDDocument()){
            PDType1Font bold=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), regular=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            int pageCount=Math.max(1,(rows.size()+25)/26);
            for(int pg=0;pg<pageCount;pg++){
                PDPage page=new PDPage(PDRectangle.A4);doc.addPage(page);
                try(PDPageContentStream cs=new PDPageContentStream(doc,page)){
                    text(cs,bold,17,48,790,INSTITUTION_NAME + " — ACADEMIC RECORDS"); text(cs,regular,10,48,773,"DEMO DOCUMENT • NOT AN OFFICIAL TRANSCRIPT"); line(cs,48,762,547,762);
                    text(cs,bold,15,48,738,all?"CONSOLIDATED MARKS MEMO":"SEMESTER MARKS MEMO"+(rows.isEmpty()?"":" — SEMESTER "+rows.get(0)[0]));
                    text(cs,regular,10,48,716,"Student: "+name);text(cs,regular,10,48,701,"Roll number: "+roll);text(cs,regular,10,48,686,"Branch: "+branch+"   Course: "+course);text(cs,regular,10,48,671,"Admission year: "+year+"   Issued: "+LocalDate.now());
                    if(photo!=null)try{BufferedImage bi=ImageIO.read(new ByteArrayInputStream(photo));if(bi!=null){var pi=org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc,bi);cs.drawImage(pi,475,670,65,70);}}catch(Exception ignored){}
                    text(cs,regular,9,48,653,"Memo ID: "+id); line(cs,48,641,547,641);
                    int y=625;text(cs,bold,9,48,y,"SEM");text(cs,bold,9,78,y,"CODE");text(cs,bold,9,150,y,"SUBJECT");text(cs,bold,9,330,y,"CR");text(cs,bold,9,365,y,"POINTS");text(cs,bold,9,415,y,"GRADE");text(cs,bold,9,470,y,"RESULT");line(cs,48,y-6,547,y-6); y-=23;
                    int from=pg*26,to=Math.min(rows.size(),from+26);int currentSem=-1;
                    for(int i=from;i<to;i++){Object[] a=rows.get(i);int sem=(int)a[0];if(all&&currentSem!=-1&&sem!=currentSem)y-=4;currentSem=sem;
                        text(cs,regular,9,48,y,String.valueOf(a[0]));text(cs,regular,9,78,y,clip((String)a[1],12));text(cs,regular,9,150,y,clip((String)a[2],29));text(cs,regular,9,330,y,String.valueOf(a[3]));text(cs,regular,9,365,y,String.valueOf(a[4]));text(cs,regular,9,415,y,String.valueOf(a[5]));text(cs,regular,9,470,y,(boolean)a[6]?"PASS":"FAIL");y-=17;
                    }
                    line(cs,48,145,547,145);
                    if(!all){double g=semesterGpa(c,roll,(int)rows.get(0)[0]);text(cs,bold,10,48,127,"SGPA: "+String.format(Locale.ROOT,"%.2f",g)+"     CGPA: "+String.format(Locale.ROOT,"%.2f",cgpaFor(c,roll))+"     Overall: "+(rows.stream().allMatch(a->(boolean)a[6])?"PASS":"FAIL"));}
                    else text(cs,bold,10,48,127,"Consolidated CGPA: "+String.format(Locale.ROOT,"%.2f",cgpaFor(c,roll))+"     Overall: "+(rows.stream().allMatch(a->(boolean)a[6])?"PASS":"FAIL"));
                    var qri=org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc,ImageIO.read(qr.toFile()));cs.drawImage(qri,48,38,64,64);
                    text(cs,regular,8,120,82,"Scan to verify memo ID");text(cs,regular,8,120,68,"Authorized signature: __________________");text(cs,regular,8,120,55,"Controller of Examinations (placeholder)");
                    text(cs,regular,8,480,45,"Page "+(pg+1)+" of "+pageCount);
                }
            }
            doc.save(out.toFile());
        }finally{Files.deleteIfExists(qr);}
    }
    private double cgpaFor(Connection c,String roll)throws Exception{try(PreparedStatement p=c.prepareStatement("SELECT SUM(credits*grade_point)/SUM(credits) FROM results WHERE roll_no=?")){p.setString(1,roll);try(ResultSet r=p.executeQuery()){return r.next()?r.getDouble(1):0;}}}
    private String clip(String s,int n){return s.length()<=n?s:s.substring(0,n-1)+"…";}
    private void text(PDPageContentStream cs,PDType1Font f,int size,float x,float y,String s)throws IOException{cs.beginText();cs.setFont(f,size);cs.newLineAtOffset(x,y);cs.showText(s.replaceAll("[^\\x20-\\x7E]","?"));cs.endText();}
    private void line(PDPageContentStream cs,float x1,float y1,float x2,float y2)throws IOException{cs.moveTo(x1,y1);cs.lineTo(x2,y2);cs.stroke();}
    private String sha256(Path p)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[] b=new byte[8192];for(int n;(n=in.read(b))>0;)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
    private void verify(){Object[] options={"Verify ID","Scan QR image","Check PDF integrity","Cancel"};int choice=JOptionPane.showOptionDialog(frame,"Select memo verification method.","Verify memo",JOptionPane.DEFAULT_OPTION,JOptionPane.QUESTION_MESSAGE,null,options,options[0]);if(choice==3||choice<0)return;String q;
        try{if(choice==1){JFileChooser fc=new JFileChooser();if(fc.showOpenDialog(frame)!=JFileChooser.APPROVE_OPTION)return;BufferedImage bi=ImageIO.read(fc.getSelectedFile());if(bi==null)throw new IllegalArgumentException("Could not read image.");q=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(bi)))).getText();}
            else if(choice==2){JFileChooser fc=new JFileChooser("memos");fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF files","pdf"));if(fc.showOpenDialog(frame)!=JFileChooser.APPROVE_OPTION)return;Path file=fc.getSelectedFile().toPath();q=file.toAbsolutePath().normalize().toString();}
            else {q=JOptionPane.showInputDialog(frame,"Enter memo ID:");if(q==null)return;q=q.trim();}
        }catch(Exception e){statusError(e);return;}
        try(Connection c=db()){
        if(choice==2){Path file=Path.of(q);String hash=sha256(file);try(PreparedStatement p=c.prepareStatement("SELECT memo_id,sha256 FROM issued_memos WHERE file_path=?")){p.setString(1,file.toAbsolutePath().normalize().toString());try(ResultSet r=p.executeQuery()){if(!r.next())error("This PDF is not registered in the trusted memo store.");else{boolean ok=hash.equalsIgnoreCase(r.getString(2));JOptionPane.showMessageDialog(frame,"Memo "+r.getString(1)+"\nSHA-256: "+(ok?"MATCH — unchanged":"MISMATCH — file changed"));}}}return;}
        if(q.startsWith("MARKS-MEMO:"))q=q.substring("MARKS-MEMO:".length());try(PreparedStatement p=c.prepareStatement("SELECT m.memo_id,m.roll_no,m.semester,m.consolidated,m.sha256,s.name FROM issued_memos m JOIN students s ON s.roll_no=m.roll_no WHERE m.memo_id=?")){p.setString(1,q);try(ResultSet r=p.executeQuery()){if(r.next())JOptionPane.showMessageDialog(frame,"VALID ISSUED RECORD\nMemo ID: "+r.getString(1)+"\nStudent: "+r.getString(6)+" ("+r.getString(2)+")\nType: "+(r.getBoolean(4)?"Consolidated":"Semester "+r.getInt(3))+"\nStored SHA-256: "+r.getString(5)+"\nTo check file integrity, select the actual PDF file.");else error("Memo ID was not found.");}}
        }catch(Exception e){statusError(e);}}
    private void deleteStudent(){String roll=selectedRoll();if(roll==null){error("Select a student first.");return;}if(JOptionPane.showConfirmDialog(frame,"Delete student "+roll+" and their results/memos?","Confirm",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;try(Connection c=db();PreparedStatement p=c.prepareStatement("DELETE FROM students WHERE roll_no=?")){p.setString(1,roll);p.executeUpdate();refresh();}catch(Exception e){statusError(e);}}
    private void dbStatus(){try(Connection c=db()){JOptionPane.showMessageDialog(frame,"MySQL connection successful.\nSchema: "+c.getCatalog());}catch(Exception e){statusError(e);}}
    private void error(String s){JOptionPane.showMessageDialog(frame,s,"Notice",JOptionPane.WARNING_MESSAGE);}
    private void statusError(Exception e){JOptionPane.showMessageDialog(frame,"Operation failed: "+e.getMessage()+"\nCheck config/db.properties, MySQL service, schema.sql, and lib JARs.","Error",JOptionPane.ERROR_MESSAGE);}
}
