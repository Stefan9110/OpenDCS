"""One module per figure, each with a NAME and a render(data, out) that returns the PDF it wrote or None."""

from . import charge, components, composition, cores, export, fit, footprint, headroom, sampling, throughput, timeline, topology

ALL = (cores, components, fit, headroom, sampling, export, charge, throughput, topology, footprint, timeline, composition)
