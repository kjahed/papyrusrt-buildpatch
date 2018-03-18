package ca.jahed.papyrusrt.buildfix;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.envvar.IEnvironmentVariable;
import org.eclipse.cdt.utils.Platform;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.papyrusrt.codegen.cpp.AbstractElementGenerator;
import org.eclipse.papyrusrt.codegen.cpp.CppCodePattern;
import org.eclipse.papyrusrt.codegen.cpp.internal.CapsuleGenerator;
import org.eclipse.papyrusrt.xtumlrt.common.Capsule;
import org.eclipse.papyrusrt.xtumlrt.common.Package;
import org.eclipse.uml2.uml.util.UMLUtil;
import org.osgi.framework.Bundle;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class ProjectFilePatcher extends CapsuleGenerator {

	private static final String UMLRTS_ROOT_VAR = "UMLRTS_ROOT";

	private static Map<String, Long> fileLastModified = new HashMap<>();
	
    public ProjectFilePatcher( CppCodePattern cpp, Capsule element, Package context ) {
        super(cpp, element);
    }

    public static class Factory implements AbstractElementGenerator.Factory<Capsule, Package> {
        @Override
        public AbstractElementGenerator create( CppCodePattern cpp, Capsule element, Package context ) {
            return new ProjectFilePatcher( cpp, element, context );
        }
    }

    @Override
    public boolean generate() {
		File projectFile = new File(cpp.getOutputFolder().getParent(), ".cproject");
		
		if(projectFile.exists()) {
			
			if(!fileLastModified.containsKey(projectFile.getAbsolutePath())
					|| fileLastModified.get(projectFile.getAbsolutePath()) < projectFile.lastModified()){
				try {
					if(patchProjectFile(projectFile.getAbsolutePath(), getUMLRTSRootEnv())) {
						reloadProject(projectFile);
					}
				} catch (Exception e) {
					System.err.println("Error patching project file");
					e.printStackTrace();
				} finally {
					fileLastModified.put(projectFile.getAbsolutePath(), projectFile.lastModified());
				}
			}
			
		} else {
			System.err.println("Can't find CDT project file");
		}
    	
    	return super.generate();
    }
    

	/**
	 * Get UMLRTS_ROOT directory.
	 * 
	 * @return UMLRTS_ROOT
	 */
	public static String getUMLRTSRootEnv() {
		String result = "";
		IEnvironmentVariable var = CCorePlugin.getDefault().getBuildEnvironmentManager()
				.getContributedEnvironment().getVariable(UMLRTS_ROOT_VAR, null);
		if (var != null) {
			result = var.getValue();
		}
		if (UMLUtil.isEmpty(result)) {
			String rtsroot = System.getenv(UMLRTS_ROOT_VAR);
			if (UMLUtil.isEmpty(rtsroot)) {
				result = getRTSRootPath();
			} else {
				result = "${UMLRTS_ROOT}";
			}
		}
		return result;
	}
	
	/**
	 * @return The full path to the RTS root directory
	 */
	public static String getRTSRootPath() {

		Bundle bundle = Platform.getBundle("org.eclipse.papyrusrt.rts");
		if (bundle != null) {
			Path path = new Path("umlrts");
			URL url = FileLocator.find(bundle, path, null);
			try {
				return FileLocator.resolve(url).getPath();
			} catch (Exception e) {
				return "";
			}
		}
		return "";
	}
	
	/**
	 * Patch a CDT project description file to add
	 * the needed libraries to the linker
	 * 
	 * @param projectFile Full path to the .cproject file
	 * @param rtsRoot Full path to the UMLRTS root folder
	 * @return true if the file was patched successfully
	 * 
	 * @throws ParserConfigurationException 
	 * @throws IOException 
	 * @throws SAXException 
	 * @throws TransformerException 
	 */
	public static boolean patchProjectFile(String projectFile, String rtsRoot) 
			throws ParserConfigurationException, SAXException, IOException, TransformerException {
		String rtsIncludeDir = new File(rtsRoot, "include").getAbsolutePath();
		String rtsLibDir = new File(new File(rtsRoot, "lib"), "linux.x86-gcc-4.6.3").getAbsolutePath();
		
		String toolchainPrefix;
		String os = System.getProperty("os.name").toLowerCase();
		if(os.startsWith("win"))
			toolchainPrefix = "gnu";
		else if(os.startsWith("mac"))
			toolchainPrefix = "macosx";
		else
			toolchainPrefix = "gnu";
		
		
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
		Document doc = docBuilder.parse(projectFile);

		Element root = (Element) doc.getElementsByTagName("cproject").item(0);
		if(!root.getAttribute("patched").isEmpty())
			return false;
		root.setAttribute("patched", "true");
		
		Element libsNode = doc.createElement("option");
		libsNode.setAttribute("id", toolchainPrefix+".cpp.link.option.libs");
		libsNode.setAttribute("superClass", toolchainPrefix+".cpp.link.option.libs");
		libsNode.setAttribute("useByScannerDiscovery", "false");
		libsNode.setAttribute("valueType", "libs");
	
		Element rtsLibNode = doc.createElement("listOptionValue");
		rtsLibNode.setAttribute("builtIn", "false");
		rtsLibNode.setAttribute("value", "rts");
		libsNode.appendChild(rtsLibNode);
		
		Element pthreadLibNode = doc.createElement("listOptionValue");
		pthreadLibNode.setAttribute("builtIn", "false");
		pthreadLibNode.setAttribute("value", "pthread");
		libsNode.appendChild(pthreadLibNode);
		
		Element libsPathNode = doc.createElement("option");
		libsPathNode.setAttribute("id", toolchainPrefix+".cpp.link.option.paths");
		libsPathNode.setAttribute("superClass", toolchainPrefix+".cpp.link.option.paths");
		libsPathNode.setAttribute("useByScannerDiscovery", "false");
		libsPathNode.setAttribute("valueType", "libPaths");
		
		Element rtsPathNode = doc.createElement("listOptionValue");
		rtsPathNode.setAttribute("builtIn", "false");
		rtsPathNode.setAttribute("value", "\""+rtsLibDir+"\"");
		libsPathNode.appendChild(rtsPathNode);
		
		NodeList tools = doc.getElementsByTagName("toolChain").item(0).getChildNodes();
		
		for (int i = 0; i < tools.getLength(); i++) {
			Node toolNode = tools.item(i);
			NamedNodeMap toolAttr = toolNode.getAttributes();
			
			if(toolAttr != null) {
				Node toolId = toolAttr.getNamedItem("id");
				if(toolId != null) {
					
					if(toolId.getTextContent().startsWith("cdt.managedbuild.tool."+toolchainPrefix+".cpp.linker")) {
						toolNode.appendChild(libsPathNode);
						toolNode.appendChild(libsNode);
					}
					
					if(toolId.getTextContent().startsWith("cdt.managedbuild.tool.gnu.cpp.compiler")) {
						NodeList compilerOptions = toolNode.getChildNodes();
						for(int j=0; j<compilerOptions.getLength(); j++) {
							Node option = compilerOptions.item(j);
							NamedNodeMap optionAttrs = option.getAttributes();
							
							if(optionAttrs != null) {
								Node optionId = optionAttrs.getNamedItem("id");
								if(optionId != null && optionId.getTextContent().startsWith("gnu.cpp.compiler.option.include.paths")) {
									Element compiler = (Element) option;
									Element includeDir = (Element) compiler.getElementsByTagName("listOptionValue").item(0);
									includeDir.setAttribute("value", "\""+rtsIncludeDir+"\"");
								}
							}
						}
					}
				}
			}
		}
		
		TransformerFactory transformerFactory = TransformerFactory.newInstance();
		Transformer transformer = transformerFactory.newTransformer();
		DOMSource source = new DOMSource(doc);
		StreamResult result = new StreamResult(new File(projectFile));
		transformer.transform(source, result);
		return true;
	}
    
	public void reloadProject(File projectFile) throws CoreException {
		String projectName = new File(projectFile.getParent()).getName();
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		project.close(new NullProgressMonitor());
		project.open(new NullProgressMonitor());
	}
}