package br.eng.rodrigogml.rfw.metaobjectgenerator;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;

public class MetaObjectGenerator extends IncrementalProjectBuilder {

  public static final String BUILDER_ID = "br.eng.rodrigogml.rfw.metaobjectgenerator.MetaObjectGenerator";

  @SuppressWarnings("rawtypes")
  @Override
  protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException {
    if (kind == IncrementalProjectBuilder.FULL_BUILD) {
      fullBuild(monitor);
    } else {
      IResourceDelta delta = getDelta(getProject());
      if (delta == null) {
        fullBuild(monitor);
      } else {
        incrementalBuild(delta, monitor);
      }
    }
    return null;
  }

  private void fullBuild(IProgressMonitor monitor) throws CoreException {
    getProject().accept(new Visitor(getProject(), monitor));
  }

  private void incrementalBuild(IResourceDelta delta, IProgressMonitor monitor) throws CoreException {
    delta.accept(new DeltaVisitor(getProject(), monitor));
  }

  @Override
  protected void startupOnInitialize() {
  }

  @Override
  protected void clean(IProgressMonitor monitor) {
  }

  @SuppressWarnings("deprecation")
  public static void createMOForResource(IResource res, IProject project, IProgressMonitor monitor) throws CoreException {
    String voPath = res.getLocation().toString();
    try {
      // l� o conte�do do resource (Classe VO)
      final byte[] encoded = Files.readAllBytes(Paths.get(voPath));

      // Faz o parser do VO para processar seu conte�do e obter as informa��es necess�rias para criar a classe do MetaObject_
      final ASTParser parser = ASTParser.newParser(AST.JLS12);
      parser.setSource(new String(encoded).toCharArray());
      final CompilationUnit vo = (CompilationUnit) parser.createAST(null);
      final VOData voData = new VOData();
      vo.accept(new ASTVisitor() {
        @Override
        public boolean visit(VariableDeclarationFragment node) {
          if (node.getName().getFullyQualifiedName().equals("serialVersionUID")) {
            voData.serialVersionUID = "" + node.getInitializer();
          } else if (node.getName().getFullyQualifiedName().equals("id")) {
            // n�o faz nada, o m�todo do parametro id � definido na superclasse RFWVO_ n�o temos de fazer nada com esse atirbuto
          } else if (node.getParent() instanceof FieldDeclaration) {
            Type type = ((FieldDeclaration) node.getParent()).getType();
            if (type.toString().endsWith("VO")) {
              voData.fieldMetas.add(node.getName().getFullyQualifiedName());
              voData.fieldMetas_Type.add(type.toString());
            } else if (type.toString().matches("List\\<.*\\>")) {
              voData.fieldLists.add(node.getName().getFullyQualifiedName());
              voData.fieldLists_Type.add(type.toString());
            } else if (type.toString().matches("Map\\<.*,.*\\>")) {
              voData.fieldMaps.add(node.getName().getFullyQualifiedName());
              voData.fieldMaps_Type.add(type.toString());
            } else {
              // Se n�o � um Field ligado � um VO, ou uma collection, tratamos como um field "comum"
              if (!(node.getParent().getParent() instanceof EnumDeclaration)) {
                voData.fields.add(node.getName().getFullyQualifiedName());
              }
            }
          }
          return super.visit(node);
        }

        @Override
        public boolean visit(MethodDeclaration node) {
          if (!node.isConstructor()) {
            if (node.getName().getFullyQualifiedName().startsWith("get")) {
              if (node.getParent() instanceof TypeDeclaration) {
                if ((((TypeDeclaration) node.getParent()).getModifiers() & Modifier.PUBLIC) > 0) {
                  // Se o m�todo come�ar com get e for p�blido, separamos o resto do nome do m�todo e incluimos na lista de m�todos
                  final String attribName = node.getName().getFullyQualifiedName().substring(3, 4).toLowerCase() + node.getName().getFullyQualifiedName().substring(4);
                  if (!"id".equals(attribName)) {
                    voData.methodGet.add(attribName);
                    voData.methodGet_Type.add(node.getReturnType2().toString());
                  }
                }
              }
            }
          }
          return super.visit(node);
        }

        @Override
        public boolean visit(PackageDeclaration node) {
          voData.pack = node.getName().getFullyQualifiedName();
          return super.visit(node);
        }

        @Override
        public boolean visit(ImportDeclaration node) {
          String imp = node.getName().getFullyQualifiedName();
          if (imp.endsWith("VO")) {
            voData.imports.put(imp.substring(imp.lastIndexOf(".") + 1, imp.length()), imp);
          }
          return super.visit(node);
        }

        @Override
        public boolean visit(CompilationUnit node) {
          return super.visit(node);
        }

        @Override
        public boolean visit(TypeDeclaration node) {
          voData.className = node.getName().getFullyQualifiedName();
          if (node.getSuperclassType() == null || !"RFWVO".equals(node.getSuperclassType().toString())) voData.skip = true;
          return super.visit(node);
        }

      });

      // Se tiver definido o parametro para pular a classe durante o parser, j� abortamos o m�todo
      if (voData.skip) return;

      // ## Depois do Parser do VO e com todas as vari�veis prontas s� falta montar o conte�do da classe e salvar
      // Primeiro passo � montar os m�todos, e a medida que encontramos VOs, tamb�m declamos os imports
      StringBuilder imports = new StringBuilder();
      StringBuilder methods = new StringBuilder();
      StringBuilder constFields = new StringBuilder();

      // J� iniciamos o imports com o import do RFWVO_ utilizando como base o import do RFWVO que normalmente h� em todos os VOs
      final String rfwvo = voData.imports.get("RFWVO"); // Retorna nulo caso n�o tenha o Import para o RFWVO. Isso pode acontecer se a classe estiver no mesmo package
      if (rfwvo != null) imports.append("import ").append(rfwvo).append("_;");

      // Lista com os atributos j� feitos para evitar que sejam criados mais de uma vez, principalmente por conta da detec��o de campos e m�todos GET do VO
      final ArrayList<String> doneAttributes = new ArrayList<>();

      // Cria os campos normais
      for (String field : voData.fields) {
        createMethod(methods, field);
        createConstField(constFields, field);
        doneAttributes.add(field);
      }

      // Cria os campos MetaObjects
      Iterator<String> i = voData.fieldMetas_Type.iterator();
      for (String field : voData.fieldMetas) {
        String type = i.next();
        createMethodVO_(methods, field, type);
        createConstField(constFields, field);
        createImport(voData, imports, type);
        doneAttributes.add(field);
      }

      // Cria os campos de listas
      i = voData.fieldLists_Type.iterator();
      for (String field : voData.fieldLists) {
        String type = i.next();
        // Para o campo do tipo Lista temos que verifica o tipo dentro da lista, se for um VO temos que criar m�todos no padr�o do VO_ e n�o de retorno de String
        if (type.endsWith("VO>")) {
          type = type.substring(type.indexOf("<") + 1, type.lastIndexOf(">"));
          createMethodVO_(methods, field, type);
          createImport(voData, imports, type);
          // Cria agora o m�todo capaz de receber o �ndice da lista
          createMethodListVO_(methods, field, type);
        } else {
          // Se o tipo da lista n�o � um VO, criamos m�todos padr�es para o field
          createMethod(methods, field);
          // Cria agora o m�todo capaz de receber o �ndice da lista
          createMethodList(methods, field);
        }
        // Cria a constante com o nome do field
        createConstField(constFields, field);
        doneAttributes.add(field);
      }

      // Cria os campos de Maps
      i = voData.fieldMaps_Type.iterator();
      for (String field : voData.fieldMaps) {
        String type = i.next();
        String keytype = type.substring(type.indexOf("<") + 1, type.lastIndexOf(","));
        // Para o campo do tipo Map temos que verifica o tipo dentro do Map, se for um VO temos que criar m�todos no padr�o do VO_ e n�o de retorno de String
        if (type.endsWith("VO>")) {
          type = type.substring(type.indexOf(",") + 1, type.lastIndexOf(">"));
          createMethodVO_(methods, field, type);
          createImport(voData, imports, type);
          // Cria agora o m�todo capaz de receber a chave do Map
          createMethodMapVO_(methods, field, type, keytype);
        } else {
          // Se o tipo da lista n�o � um VO, criamos m�todos padr�es para o field
          createMethod(methods, field);
          // Cria agora o m�todo capaz de receber a chave do Map
          createMethodMap(methods, field, keytype);
        }
        // Cria a constante com o nome do field
        createConstField(constFields, field);
        doneAttributes.add(field);
      }

      // Itera os m�todos get para ver que m�todos mais precisam ser gerados.
      i = voData.methodGet_Type.iterator();
      for (String field : voData.methodGet) {
        String type = i.next();
        if (!doneAttributes.contains(field)) {
          methods.append(System.lineSeparator()).append("/**").append(System.lineSeparator()).append(" * Este m�todo foi gerado a partir de um m�todo GET. � poss�vel que ele seja apenas um m�todo somente de leitura.").append(System.lineSeparator()).append(" */").append(System.lineSeparator());
          // Avaliamos o retorno do m�todo para saber o tipo de m�todos que vamos criar
          if (type.endsWith("VO")) { // Retorna um VO
            createMethodVO_(methods, field, type);
            createImport(voData, imports, type);
          } else if (type.toString().matches("List\\<.*VO\\>")) { // Retorna uma lista cujo tipo seja um VO
            type = type.substring(type.indexOf("<") + 1, type.lastIndexOf(">"));
            createMethodVO_(methods, field, type);
            createImport(voData, imports, type);
            // Cria agora o m�todo capaz de receber o �ndice da lista
            createMethodListVO_(methods, field, type);
          } else if (type.toString().matches("List\\<.*\\>")) { // Retorna uma lista cujo tipo n�o seja um VO
            // Se o tipo da lista n�o � um VO, criamos m�todos padr�es para o field
            createMethod(methods, field);
            // Cria agora o m�todo capaz de receber o �ndice da lista
            createMethodList(methods, field);
          } else if (type.toString().matches("Map\\<.*,.*VO\\>")) { // Retorna um map cujo tipo seja um VO
            String keytype = type.substring(type.indexOf("<") + 1, type.lastIndexOf(","));
            type = type.substring(type.indexOf(",") + 1, type.lastIndexOf(">"));
            createMethodVO_(methods, field, type);
            createImport(voData, imports, type);
            createMethodMapVO_(methods, field, type, keytype);
          } else if (type.toString().matches("Map\\<.*,.*VO\\>")) { // Retorna um map cujo tipo n�o seja um VO
            String keytype = type.substring(type.indexOf("<") + 1, type.lastIndexOf(","));
            createMethod(methods, field);
            createMethodMap(methods, field, keytype);
          } else {
            // Cria o m�todo para simular o atributo deste m�todo get que encontramos sem atributo.
            createMethod(methods, field);
          }
        }
        doneAttributes.add(field);
      }

      final String lineSep = System.getProperty("line.separator");

      // StringBuilder com o conte�do final do MetaObject_
      final StringBuilder sbClass = new StringBuilder(100);
      // Declaramos o package
      sbClass.append("package ").append(voData.pack).append(";");
      // Incluimos todos os imports
      sbClass.append(imports);
      // Coment�rio da Classe avisando sobre a autogera��o da Classe
      sbClass.append(lineSep).append("// Esta classe foi gerada utilizando o MetaObjectGenerator em ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date())).append(". N�o edite!").append(lineSep);
      // Abrimos a Classe
      sbClass.append("public class ").append(voData.className).append("_ extends RFWVO_ {");
      // inclui o SerialID
      sbClass.append("private static final long serialVersionUID = ").append(voData.serialVersionUID).append(";");
      // inclui o instance do VO padr�o
      sbClass.append("public static final ").append(voData.className).append("_ vo() { return new ").append(voData.className).append("_(); }");
      // Inclui o campo est�tido _id que � constante em todos os objetos
      sbClass.append("public final static String _id = \"id\";");
      // Inclui os campos Est�ticos para refer�ncia
      sbClass.append(constFields);
      // Inclui o Construtor padr�o da classe
      sbClass.append("private ").append(voData.className).append("_() { }");
      // Inclui o Construtor Personalizado
      sbClass.append("public ").append(voData.className).append("_(String basepath) { super(basepath); }");
      // Inclui todos os m�todos criados
      sbClass.append(methods);
      // Fecha a classe
      sbClass.append("}");

      /*
       * Formata o conte�do da classe
       */
      // take default Eclipse formatting options
      Map<?, ?> options = DefaultCodeFormatterConstants.getEclipseDefaultSettings();

      // instantiate the default code formatter with the given options
      final CodeFormatter codeFormatter = ToolFactory.createCodeFormatter(options);

      String source = sbClass.toString();
      final TextEdit edit = codeFormatter.format(CodeFormatter.K_COMPILATION_UNIT, // format a compilation unit
          source, // source to format
          0, // starting position
          source.length(), // length
          0, // initial indentation
          lineSep // line separator
      );

      IDocument document = new Document(source);
      try {
        edit.apply(document);
        source = document.get();
      } catch (Exception e) {
        // Se falhar em formatar... foda-se
        e.printStackTrace();
      }

      // Caminho para o VO_, baseado no caminho do VO
      String voPath_ = res.getProjectRelativePath().toString();
      final int li = voPath_.lastIndexOf(".");
      voPath_ = voPath_.substring(0, li) + "_" + voPath_.substring(li);
      final IFile file_ = project.getFile(voPath_);
      if (!file_.exists()) {
        file_.create(new ByteArrayInputStream(new byte[0]), true, null);
      }
      file_.setContents(new ByteArrayInputStream(source.getBytes()), false, false, null);
    } catch (Exception e) {
      e.printStackTrace();
      throw new CoreException(new Status(IStatus.ERROR, BUILDER_ID, "Falha ao gerar MO: " + voPath, e));
    }

  }

  private static void createMethod(StringBuilder methods, String field) {
    methods.append("public String ").append(field).append("() { return getAttributePath(\"").append(field).append("\"); }");
  }

  private static void createImport(final VOData voData, StringBuilder imports, String type) {
    // Procura se temos um import para esse tipo. o import pode n�o existir se estiver no mesmo package, neste caso s� ignoramos
    String imp = voData.imports.get(type);
    if (imp != null) {
      imports.append("import ").append(imp).append("_;");
      // Depois que o import j� foi criado removemos ele da hash para evitar que sejam criadas entradas duplicadas
      voData.imports.remove(type);
    }
  }

  private static void createMethodVO_(StringBuilder buff, String field, String type) {
    buff.append("public ").append(type).append("_ ").append(field).append("() { return new ").append(type).append("_(getAttributePath(\"").append(field).append("\")); }");
  }

  private static void createConstField(StringBuilder buff, String field) {
    buff.append("public final static String _").append(field).append(" = \"").append(field).append("\";");
  }

  private static void createMethodList(StringBuilder buff, String field) {
    buff.append("public String ").append(field).append("(int index) { return getAttributePath(\"").append(field).append("\", index); }");
  }

  private static void createMethodListVO_(StringBuilder buff, String field, String type) {
    buff.append("public ").append(type).append("_ ").append(field).append("(int index) { return new ").append(type).append("_(getAttributePath(\"").append(field).append("\", index)); }");
  }

  private static void createMethodMap(StringBuilder buff, String field, String keytype) {
    buff.append("public String ").append(field).append("(").append(keytype).append(" key) { return getAttributePath(\"").append(field).append("\", \"\" + key, ").append(keytype).append(".class); }");
  }

  private static void createMethodMapVO_(StringBuilder buff, String field, String type, String keytype) {
    buff.append("public ").append(type).append("_ ").append(field).append("(").append(keytype).append(" key) { return new ").append(type).append("_(getAttributePath(\"").append(field).append("\", \"\" + key, ").append(keytype).append(".class)); }");
  }
}

/**
 * Objeto simples para guardar as informa��es encontradas na classe do VO durante o parser para usar depois na composi��o do VO_
 */
class VOData {
  /**
   * Se definido como True durate o parser do VO, a classe VO_ n�o deve ser gerada.
   */
  public boolean skip = false;
  /**
   * Define o package onde da nova classe
   */
  public String pack = null;
  /**
   * Define o nome da nova classe
   */
  public String className = null;
  /**
   * Nome da classe do VO
   */
  public String voClasName = null;
  /**
   * Define o serialVersionUID
   */
  public String serialVersionUID = null;
  /**
   * Hash com os imports encontrados. A chave da Hash � o nome do elemento sendo importado, o conte�do � o importe completo.
   */
  public HashMap<String, String> imports = new HashMap<>();
  /**
   * Lista dos fields encontrados no VO para cria��o dos m�todos do MetaObject_
   */
  public ArrayList<String> fields = new ArrayList<>();
  /**
   * Lista dos campos que s�o listas.
   */
  public ArrayList<String> fieldLists = new ArrayList<>();
  /**
   * Tipos dos campos que s�o listas. Esta Lista deve ser sincronizada sempre com a lista fieldLists.
   */
  public ArrayList<String> fieldLists_Type = new ArrayList<>();
  /**
   * Lista dos campos que s�o Maps.
   */
  public ArrayList<String> fieldMaps = new ArrayList<>();
  /**
   * Tipos dos campos que s�o Maps. Esta Lista deve ser sincronizada sempre com a lista fieldLists.
   */
  public ArrayList<String> fieldMaps_Type = new ArrayList<>();
  /**
   * Lista dos campos que s�o hashs.
   */
  public ArrayList<String> fieldHashs = new ArrayList<>();
  /**
   * Lista dos campos que s�o MetaObjects.
   */
  public ArrayList<String> fieldMetas = new ArrayList<>();
  /**
   * Salva os tipos dos campos MetaObjects. Esta lista deve sempre ser sincroniza com a lista de Fieldmetas.
   */
  public ArrayList<String> fieldMetas_Type = new ArrayList<>();
  /**
   * Lista dos m�todos GETs.
   */
  public ArrayList<String> methodGet = new ArrayList<>();
  /**
   * Salva os tipos dos campos MetaObjects. Esta lista deve sempre ser sincroniza com a lista de Fieldmetas.
   */
  public ArrayList<String> methodGet_Type = new ArrayList<>();

}