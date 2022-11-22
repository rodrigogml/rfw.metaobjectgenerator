package br.eng.rodrigogml.rfw.metaobjectgenerator;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

public class DeltaVisitor implements IResourceDeltaVisitor {

  private IProject project;
  private IProgressMonitor monitor;

  public DeltaVisitor(IProject project, IProgressMonitor monitor) {
    this.project = project;
    this.monitor = monitor;
  }

  @Override
  public boolean visit(IResourceDelta res) throws CoreException {
    if (res.getResource().getName().endsWith("VO.java") && !res.getResource().getName().endsWith("RFWVO.java")) {
      MetaObjectGenerator.createMOForResource(res.getResource(), this.project, this.monitor);
    }
    return true; // Retorna true para continuar recebendo as notificações nos packages filhos
  }

}
