from opendc_profiler_plots.style import compact, log_axis, new_figure, scale


def test_compact_reads_as_plain_numbers_across_magnitudes():
    values = (0.03, 0.1, 1, 30, 30_000, 100_000, 2_500_000, 3e9)

    assert [compact(value) for value in values] == ["0.03", "0.1", "1", "30", "30k", "100k", "2.5M", "3G"]


def test_compact_appends_the_unit_after_the_magnitude():
    assert compact(0.3, "x") == "0.3x"
    assert compact(20_000, " rows") == "20k rows"


def test_compact_keeps_three_significant_digits():
    assert compact(4541.22) == "4.54k"
    assert compact(28.2073) == "28.2"
    assert compact(1.9375) == "1.94"


def tick_labels(ax, which: str) -> list[str]:
    ax.figure.canvas.draw()
    labels = ax.get_xticklabels() if which == "x" else ax.get_yticklabels()
    return [label.get_text() for label in labels if label.get_text()]


def test_scale_steps_evenly_under_two_decades():
    fig, ax = new_figure(3, 2)
    ax.plot([12, 60, 230, 391], [0, 1, 2, 3])

    scale(ax, "x", [12, 391])

    ticks = [float(tick) for tick in ax.get_xticks() if ax.get_xlim()[0] <= tick <= ax.get_xlim()[1]]
    steps = {round(after - before, 6) for before, after in zip(ticks, ticks[1:])}
    assert ax.get_xscale() == "linear"
    assert len(steps) == 1, f"the ticks {ticks} do not step evenly"


def test_scale_labels_one_tick_per_decade_from_two_decades_on():
    fig, ax = new_figure(3, 2)
    ax.plot([0, 1, 2, 3], [1, 10, 100, 1000])

    scale(ax, "y", [1, 1000], " s")

    assert ax.get_yscale() == "log"
    assert set(tick_labels(ax, "y")) <= {"0.1 s", "1 s", "10 s", "100 s", "1k s", "10k s"}


def test_log_axis_labels_decades_only_whatever_the_span():
    fig, ax = new_figure(3, 2)
    ax.plot([0.5, 0.8, 1.1], [0, 1, 2])

    log_axis(ax, "x", "x")

    assert ax.get_xscale() == "log"
    assert set(tick_labels(ax, "x")) <= {"0.1x", "1x", "10x"}
