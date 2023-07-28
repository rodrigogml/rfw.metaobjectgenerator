package br.eng.rodrigogml.rfw.metaobjectgenerator;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

public class Visitor implements IResourceVisitor {

  private IProject project;
  private IProgressMonitor monitor;

  public Visitor(IProject project, IProgressMonitor monitor) {
    this.project = project;
    this.monitor = monitor;
  }

  @Override
  public boolean visit(IResource res) throws CoreException {
    if (res.getName().endsWith("VO.java")) {
      if (!res.getName().endsWith("RFWVO.java")) {
        MetaObjectGenerator.createMOForResource(res, this.project, this.monitor);
      }
    }
    return true;
  }

}
